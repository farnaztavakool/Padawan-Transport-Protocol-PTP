import java.util.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Receiver {
    // inputs :receiver_port FileReceived.txt
    // implementing a wait-send protocol which doesnt need a buffer
    private BufferedWriter file;
    private BufferedWriter log_file;
    private DatagramSocket clientSocket;
    private int port;
    private InetAddress IP;
    private final PTP PTP_send;

    private final long start_timestamp;

    public Receiver(int port_number, String path) throws Exception {
        System.out.println("listening");
        IP = InetAddress.getByName("localhost");
        this.PTP_send = new PTP(port, IP.toString());
        this.port = port_number;
        this.file = create_file(path);
        this.log_file = create_file("Receiver_log.txt");
        createSocket();
        start_timestamp = System.currentTimeMillis();

        connect();
        listen();

    }

    public void log(String srd, String type, Integer seq, Integer size, Integer ACK) throws Exception {
        long cons = 1000;
        long time = (System.currentTimeMillis() - start_timestamp) / cons;
        log_file.write(srd + " " + time + " " + type + " " + seq + " " + size + " " + ACK);
        log_file.newLine();
    }

    public BufferedWriter create_file(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            file.createNewFile();
        }

        FileWriter fw = new FileWriter(file, true);
        return new BufferedWriter(fw);

    }

    /**
     * receives a SYN send a SYN/ACK wait for ACK then return true and listen
     * 
     * @return
     */
    public void connect() throws Exception {
        System.out.println("Listening for connection");
        while (true) {
            byte[] receiveData = new byte[1024];
            // receive from server
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);
            HashMap<String, String> packet = PTP.receive_PTP_packet(receivePacket);

            log("rcv", "S", Integer.parseInt(packet.get("seq_number")), 0, Integer.parseInt(packet.get("ACK_number")));

            if (PTP.get_flag(packet).equals("SYN")) {
                byte[] send = PTP_send.send_ACK(true, false, packet);
                DatagramPacket send_packet = new DatagramPacket(send, send.length,
                        InetAddress.getByName(packet.get("IP").split("/")[0]), Integer.parseInt(packet.get("port")));

                log("snd", "SA", PTP_send.seq_number, 0, PTP_send.last_ACK);
                clientSocket.send(send_packet);

            }
            byte[] final_ACK = new byte[1024];
            // receive from server
            DatagramPacket finalPacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(finalPacket);
            packet = PTP.receive_PTP_packet(finalPacket);
            if (PTP.get_flag(packet).equals("ACK")) {
                System.out.println("connected");
                if (PTP_send.ACK(Integer.parseInt(packet.get("ACK_number")))) {
                    log("rcv", "A", Integer.parseInt(packet.get("seq_number")), 0,
                            Integer.parseInt(packet.get("ACK_number")));
                    return;
                }
            }
        }

    }

    public void disconnect(HashMap<String, String> packet) throws Exception {
        // send FIN/ACK
        log("rcv", "F", Integer.parseInt(packet.get("seq_number")), 0, Integer.parseInt(packet.get("ACK_number")));
        byte[] send = PTP_send.send_ACK(false, true, packet);
        DatagramPacket send_packet = new DatagramPacket(send, send.length,
                InetAddress.getByName(packet.get("IP").split("/")[0]), Integer.parseInt(packet.get("port")));
        clientSocket.send(send_packet);
        log("snd", "FA", PTP_send.seq_number, 0, PTP_send.last_ACK);

        // receieve ACK
        // add timer here
        byte[] receiveData = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        clientSocket.receive(receivePacket);
        HashMap<String, String> end_ACK = PTP.receive_PTP_packet(receivePacket);

        // if it is Acked correctly close it
        if (PTP_send.ACK(Integer.parseInt(end_ACK.get("ACK_number")))) {
            file.close();
            log("rcv", "A", Integer.parseInt(end_ACK.get("seq_number")), 0,
                    Integer.parseInt(end_ACK.get("ACK_number")));
            log_file.close();
            clientSocket.close();

        }

    }

    public void listen() throws Exception {
        while (true) {
            byte[] packet = new byte[1024];
            // receive from server
            DatagramPacket datagram_packet = new DatagramPacket(packet, packet.length);
            clientSocket.receive(datagram_packet);
            HashMap<String, String> packet_map = PTP.receive_PTP_packet(datagram_packet);

            log("rcv", "D", Integer.parseInt(packet_map.get("seq_number")), 0,
                    Integer.parseInt(packet_map.get("ACK_number")));
            packet = null;
            datagram_packet = null;
            // if it is FIN start the disconnection process
            if (PTP.get_flag(packet_map).equals("FIN")) {
                disconnect(packet_map);
                return;
            }
            // read the data into the .txt file
            // TODO: data should be buffered and the buffer should be read to the .txt file
            // instead
            // buffer can be an array which is indexed via the seq number
            // flush the buffer
            file.write(packet_map.get("Data"));

            packet = PTP_send.send_ACK(false, false, packet_map);
            datagram_packet = new DatagramPacket(packet, packet.length,
                    InetAddress.getByName(packet_map.get("IP").split("/")[0]),
                    Integer.parseInt(packet_map.get("port")));

            log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
            clientSocket.send(datagram_packet);

        }

    }

    private void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        clientSocket = new DatagramSocket(null);
        clientSocket.bind(sockaddr);
    }

    public static void main(String[] args) throws Exception {
        Scanner myObj = new Scanner(System.in); // Create a Scanner object

        String[] input = myObj.nextLine().split(" ");
        System.out.println(input);
        Receiver receiver = new Receiver(Integer.parseInt(input[0]), input[1]);
    }
}
