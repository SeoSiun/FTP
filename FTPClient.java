import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;

public class FTPClient {
    final static int N=5;
    static int rcvbase=0;
    static int cntChunk;
    static int[] drop=null, timeout=null, biterror=null;
    public static void main(String argv[]) throws IOException, InterruptedException {
        Socket clientCommand;
        String command, response, filename = null;
        String[] tmp;
        byte[] header, data, send_data, received_data, ack;
        byte[][] receive_buffer = new byte[16][1005];
        Arrays.fill(receive_buffer,null);
        ControlSend.initSendBuffer();
        File file = null;
        int exit = 0, length;
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

        if (argv.length == 3) clientCommand = new Socket(argv[0], Integer.parseInt(argv[1]));
        else clientCommand = new Socket("127.0.0.1", 2020);

        DataOutputStream outToServer = new DataOutputStream(clientCommand.getOutputStream());
        BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientCommand.getInputStream()));

        while (exit == 0) {
            command = inFromUser.readLine();
            outToServer.writeBytes(command + "\n");

            switch (command.split(" ")[0]) {
                case "LIST":
                    response = inFromServer.readLine();
                    tmp = response.split(" ");
                    if (tmp[0].equals("200")) {
                        int num = Integer.parseInt(tmp[2]);
                        response = inFromServer.readLine();
                        tmp = response.split(",");
                        for (int i = 0; i < num * 2; i++) {
                            System.out.println(tmp[i] + "," + tmp[++i]);
                        }
                    } else if (tmp[0].equals("501")) {
                        System.out.println("Failed - directory name is invalid");
                    }
                    break;
                case "GET":
                    response = inFromServer.readLine();
                    tmp = response.split(" ");
                    if (tmp[0].equals("200")) {
                        int fileSize = Integer.parseInt(tmp[2]);
                        Socket clientData;
                        if (argv.length == 3) clientData = new Socket(argv[0], Integer.parseInt(argv[2]));
                        else clientData = new Socket("127.0.0.1", 2121);
                        DataInputStream dataFromServer = new DataInputStream(clientData.getInputStream());
                        DataOutputStream dataToServer = new DataOutputStream(clientData.getOutputStream());

                        tmp = command.split(" ")[1].split("/");
                        file = new File(tmp[tmp.length - 1]);
                        BufferedOutputStream dataToFile = new BufferedOutputStream(new FileOutputStream(file));

                        System.out.println("Received " + tmp[tmp.length - 1] + "  / " + fileSize + "bytes");
                        data = new byte[1000];
                        header = new byte[5];
                        received_data = new byte[1005];
                        ack = new byte[3];
                        int cntSize = 0;
                        rcvbase =0;
                        while (true) {
                            length = dataFromServer.read(received_data);
                            if(cntSize == fileSize && length==-1)break;
                            int seqNum = received_data[0];
                            if(isExistInRcvWindow(seqNum)){
                                //bit error check
                                if((received_data[1]==0 && received_data[2]==0)) {
                                    System.out.print(seqNum+" ");
                                    if (seqNum == rcvbase) {
                                        System.arraycopy(received_data, 0, header, 0, header.length);
                                        System.arraycopy(received_data, header.length, data, 0, length - header.length);
                                        int size = ((header[3] & 0xFF) << 8) + (header[4] & 0xFF);
                                        dataToFile.write(data, 0, size);
                                        cntSize += size;
                                        rcvbase = (rcvbase + 1) % 16;
                                        int temp;
                                        for (temp = rcvbase; receive_buffer[temp] != null; temp = (temp + 1) % 16) {
                                            System.arraycopy(receive_buffer[temp], 0, header, 0, header.length);
                                            System.arraycopy(receive_buffer[temp], header.length, data, 0, length - header.length);
                                            size = ((header[3] & 0xFF) << 8) + (header[4] & 0xFF);
                                            dataToFile.write(data, 0, size);
                                            cntSize += size;
                                            receive_buffer[temp] = null;
                                        }
                                        rcvbase=temp;
                                    } else {
                                        receive_buffer[received_data[0]] = received_data.clone();
                                    }
                                    ack[0] = received_data[0];
                                    dataToServer.write(ack);
                                }
                            }
                            else if(isLostedAck(seqNum) && received_data[1]==0 && received_data[2]==0){
                                ack[0]=received_data[0];
                                dataToServer.write(ack);
                            }
                        }
                        dataToFile.flush();
                        dataFromServer.close();
                        dataToServer.close();
                        dataToFile.close();
                        clientData.close();
                        drop=timeout=biterror=null;
                        System.out.println("  Completed...");
                        break;

                    } else if (tmp[0].equals("401")) {
                        System.out.println("Failed - Such file does not exist!");
                    } else if (tmp[0].equals("500")) {
                        System.out.println("Failed - Please use GET with file name");
                    }
                    break;
                case "PUT":
                    if (command.split(" ").length == 1) outToServer.writeBytes("-2\n");
                    else {
                        filename = command.split(" ")[1];
                        file = new File(filename);
                        if (!file.isFile()) {
                            outToServer.writeBytes("-1\n");
                        } else {
                            outToServer.writeBytes(file.length() + "\n");
                        }
                    }
                    response = inFromServer.readLine();
                    tmp = response.split(" ");
                    if (tmp[0].equals("200")) {
                        System.out.println(filename + " transferred   / " + file.length() + "bytes");
                        Socket clientData;
                        if (argv.length == 3) clientData = new Socket(argv[0], Integer.parseInt(argv[2]));
                        else clientData = new Socket("127.0.0.1", 2121);
                        DataOutputStream dataToServer = new DataOutputStream(clientData.getOutputStream());
                        DataInputStream dataFromServer = new DataInputStream(clientData.getInputStream());
                        BufferedInputStream dataFromFile = new BufferedInputStream(new FileInputStream(file));
                        header = new byte[5];
                        ControlSend.setSendbase(0);
                        header[0] = 0;
                        header[1] = header[2] = 0x00;
                        data = new byte[1000];
                        send_data = new byte[1005];
                        cntChunk = 0;
                        final int total = ((int)Math.ceil(file.length()/1000.0));
                        ControlSend.initIsAcked();
                        while ((length = dataFromFile.read(data)) != -1) {
                            cntChunk++;
                            boolean isDrop=initIsDrop(cntChunk), isTimeout=initIsTimeout(cntChunk), isBiterror=initIsBiterror(cntChunk);
                            header[3] = (byte) (length >> 8);
                            header[4] = (byte) length;
                            System.arraycopy(header, 0, send_data, 0, header.length);
                            System.arraycopy(data, 0, send_data, header.length, length);
                            final int seqNum = header[0];
                            ControlSend.setSend_buffer(seqNum,send_data.clone());
                            Thread acceptAck = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        byte[] ack = new byte[3];
                                        int seq=0;
                                        int l=0;
                                        while (l!=-1) {
                                            l=dataFromServer.read(ack);
                                            seq = ack[0];
                                            if (ControlSend.isExistInSendWindow(seq)) break;
                                        }
                                        System.out.println(seq + " acked ");
                                        ControlSend.setSend_buffer(seq,null);
                                        ControlSend.cancelTimer(seq);
                                        ControlSend.setIsAckedTrue(seq);
                                        if (seq == ControlSend.getSendbase()) {
                                            ControlSend.checkIsAcked();
                                        }
                                        if (ControlSend.getSendbase() == total % 16 && total == cntChunk) {
                                            drop = timeout = biterror = null;
                                            dataToServer.flush();
                                            dataToServer.close();
                                            dataFromFile.close();
                                            dataFromServer.close();
                                            clientData.close();
                                            System.out.println("  Completed...");
                                        }
                                    }catch (SocketException e){}
                                    catch (IOException e1) {
                                        e1.printStackTrace();
                                    }
                                }
                            });
                            TimerTask restartTimerTask = new TimerTask() {
                                @Override
                                public void run() {
                                    try {
                                        if(ControlSend.getSend_buffer(seqNum)!=null){
                                            System.out.println(seqNum+" timed out & retransmitted");
                                            dataToServer.write(ControlSend.getSend_buffer(seqNum));
                                        }
                                    }catch (SocketException e){}
                                    catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            };
                            TimerTask timerTask = new TimerTask() {
                                @Override
                                public void run() {
                                    try {
                                        if (ControlSend.getSend_buffer(seqNum)!=null) {
                                            System.out.println(seqNum + " timed out & retransmitted");
                                            dataToServer.write(ControlSend.getSend_buffer(seqNum));
                                            ControlSend.startTimer(seqNum, restartTimerTask);
                                        }
                                    }catch (SocketException e){}
                                    catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            };
                            //데이터 전송!
                            acceptAck.start();
                            while(true) {
                                if(ControlSend.isExistInSendWindow(seqNum)) break;
                            }
                            System.out.println(seqNum);
                            ControlSend.startTimer(seqNum,timerTask);
                            if(!isDrop){
                                if(isBiterror){
                                    send_data[1]=(byte)0xFF;
                                    send_data[2]=(byte)0xFF;
                                }
                                if(isTimeout){
                                    byte[] finalSend_data = send_data;
                                    Timer timer = new Timer();
                                    timer.schedule(new TimerTask() {
                                        @Override
                                        public void run() {
                                            try {
                                                dataToServer.write(finalSend_data);

                                            } catch (SocketException e){}
                                            catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    },2000);
                                }
                                else{
                                    dataToServer.write(send_data);
                                }
                            }
                            header[0]++;
                            if(header[0]==16) header[0]=0;
                        }
                        if(file.length()==0){
                            drop = timeout = biterror = null;
                            dataToServer.flush();
                            dataToServer.close();
                            dataFromFile.close();
                            dataFromServer.close();
                            clientData.close();
                            System.out.println("  Completed...");
                        }
                    } else if (tmp[0].equals("401")) {
                        System.out.println("Failed - Such file does not exist!");
                    } else if (tmp[0].equals("500")) {
                        System.out.println("Failed - Please use PUT with file name");
                    } else if (tmp[0].equals("501")) {
                        System.out.println("Failed for unknown reason");
                    }
                    break;
                case "CD":
                    response = inFromServer.readLine();
                    tmp = response.split(" ");
                    if (tmp[0].equals("200")) {
                        System.out.println(tmp[3]);
                    } else if (tmp[0].equals("501")) {
                        System.out.println("Failed - directory name is invalid");
                    }
                    break;
                case "QUIT":
                    inFromUser.close();
                    outToServer.close();
                    inFromServer.close();
                    clientCommand.close();
                    exit++;
                    break;
                case "DROP":
                    tmp = command.split(" ")[1].split(",");
                    drop = new int[tmp.length];
                    for(int i=0; i<tmp.length; i++){
                        drop[i]=Integer.parseInt(tmp[i].substring(1));
                    }
                    break;
                case "TIMEOUT":
                    tmp = command.split(" ")[1].split(",");
                    timeout = new int[tmp.length];
                    for(int i=0; i<tmp.length; i++){
                        timeout[i]=Integer.parseInt(tmp[i].substring(1));
                    }
                    break;
                case "BITERROR":
                    tmp = command.split(" ")[1].split(",");
                    biterror = new int[tmp.length];
                    for(int i=0; i<tmp.length; i++){
                        biterror[i]=Integer.parseInt(tmp[i].substring(1));
                    }
                    break;
            }
        }
        System.exit(0);
    }
    static boolean initIsDrop(int cntChunk){
        if(drop!=null){
            for(int i=0; i<drop.length; i++){
                if(drop[i]==cntChunk){
                    return true;
                }
            }
        }
        return false;
    }
    static boolean initIsTimeout(int cntChunk){
        if(timeout!=null){
            for(int i=0; i<timeout.length; i++){
                if(timeout[i]==cntChunk){
                    return true;
                }
            }
        }
        return false;
    }
    static boolean initIsBiterror(int cntChunk){
        if(biterror!=null){
            for(int i=0; i<biterror.length; i++){
                if(biterror[i]==cntChunk){
                    return true;
                }
            }
        }
        return false;
    }
    static boolean isExistInRcvWindow(int seqNum) {
        for (int i = 0; i < N; i++) {
            if (seqNum == (rcvbase + i) % 16) return true;
        }
        return false;
    }
    static boolean isLostedAck(int seqNum){
        for (int i = 0; i < N; i++) {
            if (seqNum == (rcvbase +i-N+16) % 16) return true;
        }
        return false;
    }
    private static class ControlSend {
        private volatile static int sendbase = 0;
        private volatile static boolean[] isAcked = new boolean[16];
        volatile static Timer[] timers = new Timer[16];
        volatile static byte[][] send_buffer = new byte[16][1005];

        synchronized static void startTimer(int seqNum, TimerTask timerTask){
            timers[seqNum] = new Timer();
            timers[seqNum].schedule(timerTask,1000);
        }

        synchronized static void cancelTimer(int seqNum){
            timers[seqNum].cancel();
        }
        static void initSendBuffer(){
            Arrays.fill(send_buffer,null);
        }
        synchronized static void setSend_buffer(int seqNum,byte[] sendData){
            send_buffer[seqNum] = sendData;
        }

        synchronized static byte[] getSend_buffer(int seqNum){
            return send_buffer[seqNum];
        }
        static void initIsAcked(){
            Arrays.fill(isAcked,false);
        }
        synchronized static void setIsAckedTrue(int seqNum){
            isAcked[seqNum] = true;
        }
        synchronized static void checkIsAcked() {
            int i;
            for (i = sendbase; isAcked[i]; i = (i + 1) % 16) {
                isAcked[i] = false;
            }
            sendbase = i;
        }

        synchronized static boolean isExistInSendWindow(int seqNum) {
            for (int i = 0; i < N; i++) {
                if (seqNum == (sendbase + i) % 16) return true;
            }
            return false;
        }

        synchronized static int getSendbase() {
            return sendbase;
        }

        static void setSendbase(int sb) {
            sendbase = sb;
        }
    }
}
