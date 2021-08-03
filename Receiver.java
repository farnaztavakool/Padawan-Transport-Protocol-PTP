import java.io.*;
import java.net.*;
import java.util.*;
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
    private int expected_seq;
    private final long start_timestamp;
    // 2 dimentional arraylist to buffer data based on seq_number
    private ArrayList<HashMap<Integer, String>> buffer = new ArrayList<HashMap<Integer, String>>();
    // private max_buffer_size

    public Receiver(int port_number, String path) throws Exception {
        System.out.println("listening");
        IP = InetAddress.getByName("localhost");
        // both are starting from seq number 0
        expected_seq = 0;
        this.port = port_number;
        this.PTP_send = new PTP(port, IP.toString());
        this.file = create_file(path);
        this.log_file = create_file("Receiver_log.txt");
        createSocket();
        start_timestamp = System.currentTimeMillis();

        connect();
        // expected_seq increases by from SYN
        expected_seq += 1;
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

            HashMap<String, String> packet = receive();

            if (PTP.get_flag(packet).equals("S")) {
                byte[] send_byte = PTP_send.send_ACK(true, false, packet);
                send(send_byte, packet);
                log("snd", "SA", PTP_send.seq_number, 0, PTP_send.last_ACK);

            }
            packet = receive();
            if (PTP.get_flag(packet).equals("A")) {
                System.out.println("connected");
                if (PTP_send.ACK(Integer.parseInt(packet.get("ACK_number")))) {
                    log("rcv", "A", Integer.parseInt(packet.get("seq_number")), 0,
                            Integer.parseInt(packet.get("ACK_number")));
                    return;
                }
            }
        }

    }

    public void send(byte[] send_byte, HashMap<String, String> packet) throws Exception {
        DatagramPacket send_packet = new DatagramPacket(send_byte, send_byte.length,
                InetAddress.getByName(packet.get("IP").split("/")[0]), Integer.parseInt(packet.get("port")));

        clientSocket.send(send_packet);
        System.out.println("sent");

    }

    public HashMap<String, String> receive() throws Exception {
        byte[] receiveData = new byte[1024];
        // receive from server
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        clientSocket.receive(receivePacket);
        HashMap<String, String> packet = PTP.receive_PTP_packet(receivePacket);
        String type = PTP_send.get_flag(packet);
        log("rcv", type, Integer.parseInt(packet.get("seq_number")), 0, Integer.parseInt(packet.get("ACK_number")));
        return packet;
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

            HashMap<String, String> packet_map = receive();
            log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
            if (PTP.get_flag(packet_map).equals("F")) {
                disconnect(packet_map);
                return;
            }
            // if we are receiving the packet expecting, write it into the file
            // sort the buffer and write what is missing into buffer
            // update ACK
            Integer seq_number = Integer.parseInt(packet_map.get("seq_number"));
            String data = packet_map.get("Data");
            if (seq_number == expected_seq) {

                System.out.println("ackiing");
                expected_seq += data.length();
                file.write(data);
                Collections.sort(buffer, comparator);
                while (!buffer.isEmpty()) {
                    System.out.println(buffer);
                    Integer seq = buffer.get(0).keySet().iterator().next();
                    System.out.println("are these equal? " + seq + " " + PTP_send.last_ACK);
                    if (expected_seq == seq) {
                        String data_to_write = buffer.get(0).get(seq);
                        file.write(data_to_write);
                        buffer.remove(0);
                        expected_seq += data_to_write.length();
                        continue;
                    }
                    return;

                }

                send(PTP_send.send_ACK(expected_seq), packet_map);
                // send(PTP_send.send_ACK(false, false, packet_map), packet_map);

            } else if (seq_number < expected_seq) {
                // there is duplicate packet send
                System.out.println("this is duplicated");

                send(PTP_send.send_last_ack(), packet_map);
            } else {
                // if the packet is out of order, save it to buffer but dont update the ACK
                HashMap<Integer, String> input = new HashMap<Integer, String>();
                input.put(seq_number, data);
                buffer.add(input);
                send(PTP_send.send_last_ack(), packet_map);

            }

            log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
        }

    }

    private void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        clientSocket = new DatagramSocket(null);
        clientSocket.bind(sockaddr);
    }

    final Comparator<HashMap<Integer, String>> comparator = new Comparator<HashMap<Integer, String>>() {

        @Override
        public int compare(HashMap<Integer, String> pList1, HashMap<Integer, String> pList2) {

            Integer key1 = pList1.keySet().iterator().next();
            Integer key2 = pList1.keySet().iterator().next();
            return key1.compareTo(key2);
        }
    };

    public static void main(String[] args) throws Exception {
        Scanner myObj = new Scanner(System.in); // Create a Scanner object

        String[] input = myObj.nextLine().split(" ");
        System.out.println(input);
        Receiver receiver = new Receiver(Integer.parseInt(input[0]), input[1]);
    }
}
