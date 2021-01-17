import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

public class FTPServer {
    private static Path currentDirectory=Paths.get(new Properties(System.getProperties()).getProperty("user.dir"));
    final static int N = 5;
    static int rcvbase=0;
    static int[] drop=null, timeout=null,biterror=null;
    static int cntChunk;

    public static void main(String argv[]) throws IOException, InterruptedException {
        ServerSocket serverCommand, serverData;
        Socket commandConnection;
        String command, response;
        byte[] header,data,send_data,received_data,ack;
        Path path;
        byte[][] receive_buffer = new byte[16][1005];
        Arrays.fill(receive_buffer,null);
        ControlSend.initSendBuffer();
        String[] tmp;
        int exit, length;

        if(argv.length==2){
            serverCommand = new ServerSocket(Integer.parseInt(argv[0]));
            serverData = new ServerSocket(Integer.parseInt(argv[1]));
        }
        else{
            serverCommand = new ServerSocket(2020);
            serverData = new ServerSocket(2121);
        }
        while(true) {
            commandConnection = serverCommand.accept();
            BufferedReader inFromClient = new BufferedReader(new InputStreamReader(commandConnection.getInputStream()));
            DataOutputStream outToClient = new DataOutputStream(commandConnection.getOutputStream());
            exit=0;
            while (exit == 0) {
                command = inFromClient.readLine();
                System.out.println("Request: " + command);

                switch (command.split(" ")[0]) {
                    case "LIST":
                        if(command.split(" ").length==1){
                            path = currentDirectory;
                        }
                        else {
                            path = Paths.get(command.split(" ")[1]).normalize();
                            if (!path.isAbsolute()) {
                                if (command.split(" ")[1].equals(".")) path = currentDirectory;
                                else path = changeToAbsolute(path);
                            }
                            if (!path.toFile().isDirectory()) {
                                response = "501 Failed - Directory name is invalid\n";
                                outToClient.writeBytes(response);
                                System.out.print("Response: " + response);
                                break;
                            }
                        }
                        File[] fileList = path.toFile().listFiles();
                        if (fileList.length == 0 || fileList.length == 1)
                            response = 200 + " Comprising " + fileList.length + " entry\n";
                        else response = 200 + " Comprising " + fileList.length + " entries\n";
                        outToClient.writeBytes(response);
                        System.out.print("Response: " + response);

                        response = "";
                        for (int i = 0; i < fileList.length; i++) {
                            if (i != 0) response += ",";
                            response += fileList[i].getName();
                            if (fileList[i].isDirectory()) response += ",-";
                            else if (fileList[i].isFile()) response += "," + fileList[i].length();
                        }
                        outToClient.writeBytes(response + "\n" );
                        break;
                    case "GET":
                        if(command.split(" ").length==1){
                            response = "500 Failed - Please use GET with file name\n";
                            outToClient.writeBytes(response);
                            System.out.print("Response: " + response);
                            break;
                        }
                        path = Paths.get(command.split(" ")[1]).normalize();
                        if(!path.isAbsolute()){
                            if(command.split(" ")[1].equals(".")) path = currentDirectory;
                            else path = changeToAbsolute(path);
                        }
                        if (!path.toFile().isFile()) {
                            response = "401 Failed - No such file exists\n";
                            outToClient.writeBytes(response);
                            System.out.print("Response: " + response);
                            break;
                        }
                        response = "200 Containing " + path.toFile().length() + " bytes in total\n";
                        System.out.print("Response: " + response);
                        outToClient.writeBytes(response);
                        Socket dataConnection = serverData.accept();
                        DataOutputStream dataToClient = new DataOutputStream(dataConnection.getOutputStream());
                        DataInputStream dataFromClient = new DataInputStream(dataConnection.getInputStream());
                        BufferedInputStream dataFromFile = new BufferedInputStream(new FileInputStream(path.toFile()));
                        header = new byte[5];
                        header[0]=0;
                        data = new byte[1000];
                        send_data = new byte[1005];
                        final int total = ((int)Math.ceil(path.toFile().length()/1000.0));
                        ControlSend.initIsAcked();
                        cntChunk=0;
                        ControlSend.setSendbase(0);
                        while ((length = dataFromFile.read(data)) != -1) {
                            cntChunk++;
                            boolean isDrop=initIsDrop(cntChunk), isTimeout=initIsTimeout(cntChunk), isBiterror=initIsBiterror(cntChunk);
                            header[3]=(byte)(length>>8);
                            header[4]=(byte)length;
                            System.arraycopy(header,0,send_data,0,header.length);
                            System.arraycopy(data,0,send_data,header.length,length);
                            final int seqNum = header[0];
                            ControlSend.setSend_buffer(seqNum,send_data.clone());
                            Thread acceptAck = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    byte[] ack = new byte[3];
                                    int seq=0;
                                    try {
                                        int l=0;
                                        while (l!=-1) {
                                            l=dataFromClient.read(ack);
                                            seq = ack[0];
                                            if (ControlSend.isExistInSendWindow(seq)) break;
                                        }
                                        ControlSend.setSend_buffer(seq,null);
                                        ControlSend.cancelTimer(seq);
                                        ControlSend.setIsAckedTrue(seq);
                                        if (seq == ControlSend.getSendbase()) {
                                            ControlSend.checkIsAcked();
                                        }
                                        if (ControlSend.getSendbase() == total % 16 && total == cntChunk) {
                                            drop = timeout = biterror = null;
                                            dataToClient.flush();
                                            dataToClient.close();
                                            dataFromFile.close();
                                            dataFromClient.close();
                                            dataConnection.close();
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
                                            dataToClient.write(ControlSend.getSend_buffer(seqNum));
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
                                            dataToClient.write(ControlSend.getSend_buffer(seqNum));
                                            ControlSend.startTimer(seqNum, restartTimerTask);
                                        }
                                    }catch (SocketException e){}
                                    catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            };
                        
                            acceptAck.start();
                            while(true) {
                                if(ControlSend.isExistInSendWindow(seqNum)) break;
                            }
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
                                                dataToClient.write(finalSend_data);
                                            }catch (SocketException e){}
                                            catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    },2000);
                                }
                                else{
                                    dataToClient.write(send_data);
                                }
                            }
                            header[0]++;
                            if(header[0]==16) header[0]=0;
                        }
                        if(path.toFile().length()==0){
                            drop = timeout = biterror = null;
                            dataToClient.flush();
                            dataToClient.close();
                            dataFromFile.close();
                            dataFromClient.close();
                            dataConnection.close();
                        }
                        break;
                    case "PUT":
                        int fileSize = Integer.parseInt(inFromClient.readLine());
                        if(fileSize == -1){
                            response = "401 Failed - No such file exists\n";
                            outToClient.writeBytes(response);
                            System.out.print("Response: "+response);
                            break;
                        }
                        if(fileSize == -2){
                            response = "500 Failed - Please use PUT with file name\n";
                            outToClient.writeBytes(response);
                            System.out.print("Response: "+response);
                            break;
                        }
                        System.out.println("Request: "+fileSize);
                        response = "200 Ready to receive\n";
                        outToClient.writeBytes(response);
                        System.out.print("Response: " + response);

                        path = currentDirectory.resolve(Paths.get(command.split(" ")[1]));

                        dataConnection = serverData.accept();
                        dataFromClient = new DataInputStream(dataConnection.getInputStream());
                        dataToClient = new DataOutputStream(dataConnection.getOutputStream());
                        BufferedOutputStream dataToFile = new BufferedOutputStream(new FileOutputStream(path.toFile()));

                        data = new byte[1000];
                        header = new byte[5];
                        received_data = new byte[1005];
                        ack = new byte[3];
                        int cntSize =0;
                        rcvbase = 0;
                        while (true) {
                            length = dataFromClient.read(received_data);
                            
                            if(cntSize == fileSize && length==-1)break;
                            int seqNum = received_data[0];
                            if(isExistInRcvWindow(seqNum)){
                                //bit error check
                                if((received_data[1]==0 && received_data[2]==0)) {
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
                                    dataToClient.write(ack);
                                }
                            }
                            else if(isLostedAck(seqNum) && received_data[1]==0 && received_data[2]==0){
                                ack[0]=received_data[0];
                                dataToClient.write(ack);
                            }
                        }
                        dataToFile.flush();
                        dataFromClient.close();
                        dataToClient.close();
                        dataToFile.close();
                        dataConnection.close();
                        drop=timeout=biterror=null;
                        break;
                    case "CD":
                        if (command.split(" ").length == 1) {
                            response = "200 Moved to " + currentDirectory.toString() + "\n";
                            outToClient.writeBytes(response);
                            System.out.print("Response: " + response);
                            break;
                        }
                        path = Paths.get(command.split(" ")[1]).normalize();
                        if(!path.isAbsolute()) path = changeToAbsolute(path);
                        if (!path.toFile().isDirectory()) {
                            response = "501 Failed - directory name is invalid\n";
                            outToClient.writeBytes(response);
                            System.out.print("Response: " + response);
                            break;
                        }
                        currentDirectory = path;
                        response = "200 Moved to " + currentDirectory.toString() + "\n";
                        outToClient.writeBytes(response);
                        System.out.print("Response: " + response);
                        break;
                    case "QUIT":
                        inFromClient.close();
                        outToClient.close();
                        commandConnection.close();
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
                System.out.println();
            }
        }
    }
    private static Path changeToAbsolute(Path path) {
        String[] strings = path.toString().split("\\\\");
        for (int i = 0; i < strings.length; i++) {
            if (strings[i].equals("..")) {
                if (i == 0) path = currentDirectory.getParent().toAbsolutePath();
                else path = path.getParent().toAbsolutePath();
            } else {
                if (i == 0) path = currentDirectory.resolve(Paths.get(strings[i]));
                else path = path.resolve(Paths.get(strings[i]));
            }
        }
        return path;
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
    private static class ControlSend{
        private volatile static int sendbase=0;
        volatile static boolean[] isAcked = new boolean[16];
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

        synchronized static void checkIsAcked(){
            int i;
            for (i = sendbase; isAcked[i]; i = (i + 1) % 16) {
                isAcked[i] = false;
            }
            sendbase = i;
        }
        synchronized static  boolean isExistInSendWindow(int seqNum) {
            for (int i = 0; i <  N; i++) {
                if (seqNum == (sendbase + i) % 16) return true;
            }
            return false;
        }
        synchronized static int getSendbase(){
            return sendbase;
        }
        static void setSendbase(int sb){
            sendbase = sb;
        }
    }
}
