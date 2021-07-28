import java.util.*;
import java.io.*;
import java.net.*;

public class Receiever {
    // inputs :receiver_port FileReceived.txt
    // implementing a wait-send protocol which doesnt need a buffer
    private File file;
    private DatagramSocket clientSocket;
    private int port;
    private InetAddress IP;
    private final PTP PTP_send;

    public Receiever(int port_number, String path) throws Exception {
        IP = InetAddress.getByName("localhost");
        this.PTP_send = new PTP(port, IP.toString());
        this.port = port_number;
        openFile(path);
        createSocket();
        listen();

    }

    private void openFile(String path) {

        File file = new File(path);
        this.file = file;

    }

    /**
     * receives a SYN send a SYN/ACK wait for ACK then return true and listen
     * 
     * @return
     */
    public void connect() throws Exception {
        while (true) {
            byte[] receiveData = new byte[1024];
            // receive from server
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);
            HashMap<String, String> packet = PTP.receive_PTP_packet(receivePacket);

            if (PTP.get_flag(packet).equals("SYN")) {
                byte[] send = PTP_send.send_ACK(true);
                DatagramPacket send_packet = new DatagramPacket(send, send.length,
                        InetAddress.getByName(packet.get("IP")), Integer.parseInt(packet.get("")));
                clientSocket.send(send_packet);
            }
            byte[] final_ACK = new byte[1024];
            // receive from server
            DatagramPacket finalPacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(finalPacket);
            packet = PTP.receive_PTP_packet(finalPacket);
            if (PTP.get_flag(packet).equals("ACK")) {
                if (PTP_send.ACK(packet.get("ACK_number")))
                    return;
            }
        }

    }

    public void listen() {

    }

    private void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        clientSocket = new DatagramSocket();
        clientSocket.bind(sockaddr);
    }

    public static void main(String[] args) {

    }
}
