import java.util.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Sender {
    private FileInputStream file;
    private DatagramSocket socket;
    private BufferedWriter log_file;
    private int port;
    private InetAddress IP;
    private final PTP PTP_send;
    private final int receiver_port;
    private final InetAddress receiver_IP;
    private final int MWS;
    private final int MSS;
    private final int timeout;
    private final float pdrop;

    private final long start_timestamp;
    private final int seed;
    private Random random;

    public Sender(String receiver_IP, int receiver_port, String path, int MWS, int MSS, int timeout, float pdrop,
            int seed) throws Exception {
        random = new Random(seed);
        file = new FileInputStream(path);
        create_log();
        this.IP = InetAddress.getByName("localhost");
        this.port = 3005;
        this.receiver_IP = InetAddress.getByName(receiver_IP);
        this.receiver_port = receiver_port;
        this.MWS = MWS;
        this.MSS = MSS;
        this.timeout = timeout;
        this.pdrop = pdrop;
        this.seed = seed;
        createSocket();
        this.PTP_send = new PTP(port, IP.toString());
        start_timestamp = System.currentTimeMillis();
        connect();
        send();

    }

    public void create_log() throws IOException {
        File file = new File("Sender_log.txt");
        FileWriter fw = new FileWriter(file, true);
        this.log_file = new BufferedWriter(fw);
        log_file.write("flag " + "time " + "type " + "seq number " + "size " + "ack");
        log_file.newLine();
    }

    public void log(String srd, String type, Integer seq, Integer size, Integer ACK) throws Exception {
        long cons = 1000;
        long time = (System.currentTimeMillis() - start_timestamp);
        log_file.write(srd + " " + time + " " + type + " " + seq + " " + size + " " + ACK);
        log_file.newLine();
    }

    public void wait_for_ACK(byte[] send) throws Exception {
        Timer timer = new Timer(); // timer is ticking
        TimerTask retransmit = new TimerTask() {
            public void run() {
                System.out.println("retransmitting");
                DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
                try {
                    try {
                        log("snd", "D", PTP_send.seq_number, MSS, PTP_send.last_ACK);
                    } catch (Exception e) {
                        System.out.println(e);
                    }
                    socket.send(send_packet);
                } catch (IOException e) {
                    System.out.println(e);
                }
            }
        };
        System.out.println("this is timeout" + timeout);
        timer.schedule(retransmit, timeout);
        byte[] receieveData = new byte[1024];
        DatagramPacket packet = new DatagramPacket(receieveData, receieveData.length);
        socket.receive(packet);

        if (PTP_send.ACK(Integer.parseInt(PTP.receive_PTP_packet(packet).get("ACK_number")))) {
            timer.cancel();
            return;
        }
        // System.out.println("ACK doesnt match");

    }

    public void send() throws Exception {
        byte[] data = new byte[MSS + 1];
        int bytesRead = file.read(data, 0, MSS);
        while (bytesRead != -1) {
            byte[] send = PTP_send.send_data(data);
            DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
            log("snd", "D", PTP_send.seq_number, MSS, PTP_send.last_ACK);
            // if x > pdrop transmit the file
            if (!PL())
                socket.send(send_packet);
            else {
                System.out.println("dropped");
            }
            wait_for_ACK(send);
            bytesRead = file.read(data, 0, MSS);
        }
        disconnect();
    }

    private void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        socket = new DatagramSocket(null);
        socket.bind(sockaddr);
    }

    public void connect() throws Exception {
        while (true) {
            System.out.println("Sending SYN");
            byte[] sendData = PTP_send.send_SYN();

            DatagramPacket packet = new DatagramPacket(sendData, sendData.length, receiver_IP, receiver_port);
            socket.send(packet);
            log("snd", "S", PTP_send.seq_number, 0, PTP_send.last_ACK);

            byte[] receieveData = new byte[1024];
            packet = new DatagramPacket(receieveData, receieveData.length);
            socket.receive(packet);
            HashMap<String, String> packet_recieved = PTP.receive_PTP_packet(packet);
            if (PTP.get_flag(packet_recieved).equals("SYN/ACK")) {
                log("rcv", "SA", Integer.parseInt(packet_recieved.get("seq_number")), 0,
                        Integer.parseInt(packet_recieved.get("ACK_number")));
                System.out.println("receieved SYN/ACK sending ACK");
                byte[] send = PTP_send.send_ACK(false, false, packet_recieved);
                DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
                socket.send(send_packet);
                log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
                System.out.println("successfully connected");
                return;

            }

        }
    }

    public void disconnect() throws Exception {
        // sending FIN
        byte[] send = PTP_send.send_FIN();
        DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
        socket.send(send_packet);
        log("snd", "F", PTP_send.seq_number, 0, PTP_send.last_ACK);
        send = new byte[1024];

        send_packet = new DatagramPacket(send, send.length);

        // waiting for FIN/ACK
        socket.receive(send_packet);
        HashMap<String, String> end_ACK = PTP.receive_PTP_packet(send_packet);

        // if the packet is FIN/ACK
        if (PTP.get_flag(end_ACK).equals("FIN/ACK")) {
            send = null;
            send = PTP_send.send_ACK(false, false, end_ACK);
            log("rcv", "FA", Integer.parseInt(end_ACK.get("seq_number")), 0,
                    Integer.parseInt(end_ACK.get("ACK_number")));

            // send the ACk and close the connection
            send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
            socket.send(send_packet);
            socket.close();
            log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
            log_file.close();

        }

    }

    // if x > prdrop dont drop
    // if x <= pdrop drop
    public boolean PL() {
        float x = random.nextFloat();
        if (x > pdrop)
            return false;
        else
            return true;
    }

    public static void main(String[] args) throws Exception {
        Scanner myObj = new Scanner(System.in); // Create a Scanner object

        String[] input = myObj.nextLine().split(" ");
        Sender sender = new Sender(input[0], Integer.parseInt(input[1]), input[2], Integer.parseInt(input[3]),
                Integer.parseInt(input[4]), Integer.parseInt(input[5]), Float.parseFloat(input[6]),
                Integer.parseInt(input[7]));
    }

}