import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {
    // inputs :receiver_port FileReceived.txt
    // implementing a wait-send protocol which doesnt need a buffer
    private BufferedWriter file;
    private BufferedWriter log_file;
    private BufferedWriter log_seq;
    private DatagramSocket clientSocket;
    private int port;
    private InetAddress IP;
    private final PTP PTP_send;
    private int expected_seq;
    private final long start_timestamp;
    public int segments;
    public int duplicate_segments;
    public String path;

    // 2 dimentional arraylist to buffer data based on seq_number
    private ArrayList<HashMap<Integer, String>> buffer = new ArrayList<HashMap<Integer, String>>();
    // private max_buffer_size

    public Receiver(int port_number, String path) throws Exception {
        this.path = path;
        IP = InetAddress.getByName("localhost");
        // both are starting from seq number 0
        expected_seq = segments = duplicate_segments = 0;
        this.port = port_number;
        this.PTP_send = new PTP(port, IP.toString());
        this.file = create_file(path);
        this.log_file = create_file("Receiver_log.txt");
        this.log_seq = create_file("log_seq.txt");
        createSocket();
        start_timestamp = System.currentTimeMillis();

        connect();
        // expected_seq increases by from SYN
        expected_seq += 1;
        listen();

    }

    public void log(String srd, String type, Integer seq, Integer size, Integer ACK) throws Exception {

        long time = System.currentTimeMillis() - start_timestamp;
        log_file.write(srd + " " + time + " " + type + " " + seq + " " + size + " " + ACK);
        log_file.newLine();
    }

    public void seq_log(Integer seq) throws Exception {
        log_seq.write(seq.toString() + ", ");

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
        while (true) {

            HashMap<String, String> packet = receive();

            if (PTP.get_flag(packet).equals("S")) {
                byte[] send_byte = PTP_send.send_ACK(true, false, packet);
                send(send_byte, packet);
                log("snd", "SA", PTP_send.seq_number, 0, PTP_send.last_ACK);

            }
            packet = receive();
            if (PTP.get_flag(packet).equals("A")) {
                if (PTP_send.ACK(Integer.parseInt(packet.get("ACK_number")))) {
                    return;
                }
            }
        }

    }

    public void send(byte[] send_byte, HashMap<String, String> packet) throws Exception {
        DatagramPacket send_packet = new DatagramPacket(send_byte, send_byte.length,
                InetAddress.getByName(packet.get("IP").split("/")[0]), Integer.parseInt(packet.get("port")));

        clientSocket.send(send_packet);

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

    public void log(String s) throws Exception {
        log_file.write(s);
        log_file.newLine();
    }

    public void disconnect(HashMap<String, String> packet) throws Exception {
        // send FIN/ACK
        byte[] send = PTP_send.send_ACK(false, true, packet);
        send(send, packet);
        log("snd", "FA", PTP_send.seq_number, 0, PTP_send.last_ACK);

        // receieve ACK

        HashMap<String, String> end_ACK = receive();

        file.close();
        log("Amount of (original) Data Received (in bytes): " + get_file_length(path));
        log("Number of (original) Data Segments Received: " + segments);
        log("Number of duplicate segments received: " + duplicate_segments);
        log_file.close();
        log_seq.close();
        clientSocket.close();

    }

    public int get_file_length(String path) {
        File f = new File(path);
        return (int) f.length();
    }

    public void listen() throws Exception {
        while (true) {

            HashMap<String, String> packet_map = receive();
            if (PTP.get_flag(packet_map).equals("F")) {

                disconnect(packet_map);
                return;
            }
            // if we are receiving the packet expecting, write it into the file
            // sort the buffer and write what is missing into buffer
            // update ACK
            Integer seq_number = Integer.parseInt(packet_map.get("seq_number"));
            seq_log(seq_number);
            String data = packet_map.get("Data");
            if (seq_number == expected_seq) {

                // System.out.println("ackiing");
                expected_seq += data.length();
                file.write(data);
                Collections.sort(buffer, comparator);
                segments += 1;
                while (!buffer.isEmpty()) {
                    Integer seq = buffer.get(0).keySet().iterator().next();
                    if (expected_seq == seq) {
                        String data_to_write = buffer.get(0).get(seq);
                        file.write(data_to_write);
                        buffer.remove(0);
                        expected_seq += data_to_write.length();
                        continue;
                    } else {
                        break;
                    }

                }

                send(PTP_send.send_ACK(expected_seq), packet_map);

            } else if (seq_number < expected_seq) {
                // there is duplicate packet send
                duplicate_segments += 1;
                send(PTP_send.send_last_ack(), packet_map);
            } else {
                // if the packet is out of order, save it to buffer but dont update the ACK
                HashMap<Integer, String> input = new HashMap<Integer, String>();
                segments += 1;
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

    // comapring packet in the buffer based on their sequence number
    final Comparator<HashMap<Integer, String>> comparator = new Comparator<HashMap<Integer, String>>() {

        @Override
        public int compare(HashMap<Integer, String> pList1, HashMap<Integer, String> pList2) {

            Integer key1 = pList1.keySet().iterator().next();
            Integer key2 = pList1.keySet().iterator().next();
            return key1.compareTo(key2);
        }
    };

    public static void main(String[] args) throws Exception {

        Receiver receiver = new Receiver(Integer.parseInt(args[0]), args[1]);
    }
}
