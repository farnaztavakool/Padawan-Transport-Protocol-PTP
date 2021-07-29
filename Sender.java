import java.util.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Sender {
    private File file;
    private DatagramSocket clientSocket;
    private int port;
    private InetAddress IP;
    private final PTP PTP_send;
    private final int receiver_port;
    private final InetAddress receiver_IP;
    private final int MWS;
    private final int MSS;
    private final int timeout;
    private final int pdrop;

    private final int seed;

    public Sender(String receiver_IP, int receiver_port, String path, int MWS, int MSS, int timeout, int pdrop,
            int seed) throws Exception {

        this.IP = InetAddress.getByName("localhost");
        this.port = 3003;
        this.receiver_IP = InetAddress.getByName(receiver_IP);
        this.receiver_port = receiver_port;
        this.MWS = MWS;
        this.MSS = MSS;
        this.timeout = timeout;
        this.pdrop = pdrop;
        this.seed = seed;
        createSocket();
        this.PTP_send = new PTP(port, IP.toString());
        connect();

    }

    private void openFile(String path) {

        File file = new File(path);
        this.file = file;

    }

    private void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        clientSocket = new DatagramSocket(null);
        clientSocket.bind(sockaddr);
    }

    public void connect() throws Exception {
        while (true) {
            System.out.println("Sending SYN");
            byte[] sendData = PTP_send.send_SYN();

            DatagramPacket packet = new DatagramPacket(sendData, sendData.length, receiver_IP, receiver_port);
            clientSocket.send(packet);

            byte[] receieveData = new byte[1024];
            packet = new DatagramPacket(receieveData, receieveData.length);
            clientSocket.receive(packet);
            HashMap<String, String> packet_recieved = PTP.receive_PTP_packet(packet);
            if (PTP.get_flag(packet_recieved).equals("SYN/ACK")) {
                System.out.println("receieved SYN/ACK sending ACK");
                byte[] send = PTP_send.send_ACK(false, packet_recieved);
                DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
                clientSocket.send(send_packet);
                System.out.println("successfully connected");
                return;

            }

        }
    }

    public static void main(String[] args) throws Exception {
        Scanner myObj = new Scanner(System.in); // Create a Scanner object

        String[] input = myObj.nextLine().split(" ");
        Sender sender = new Sender(input[0], Integer.parseInt(input[1]), input[2], Integer.parseInt(input[3]),
                Integer.parseInt(input[4]), Integer.parseInt(input[5]), Integer.parseInt(input[6]),
                Integer.parseInt(input[7]));
    }

}