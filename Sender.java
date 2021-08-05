import java.util.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Sender extends Thread {
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
    private Timer current_timer;
    private final long start_timestamp;
    AtomicInteger startWindow = new AtomicInteger(0);
    private int nextSeqNumber;
    private Random random;
    private HashMap<Integer, String> buffer;
    static ReentrantLock syncLock = new ReentrantLock();
    public int dupACK;
    public int dupACK_overall;
    public int retransmitted_segment;
    public int dropped_packet;
    public int num_segments;
    public int dupEnd;
    public volatile boolean stop_recv = false;
    public volatile boolean stop_send = false;
    private static long fileLength;

    public Sender(String receiver_IP, int receiver_port, String path, int MWS, int MSS, int timeout, float pdrop,
            int seed) throws Exception {
        nextSeqNumber = dupACK = dupACK_overall = retransmitted_segment = num_segments = dupEnd = 0;
        buffer = new HashMap<Integer, String>();
        random = new Random(seed);
        file = new FileInputStream(path);
        getLengthOfFile(path);
        create_log();
        this.IP = InetAddress.getByName("localhost");
        this.port = 8000;
        this.receiver_IP = InetAddress.getByName(receiver_IP);
        this.receiver_port = receiver_port;
        this.MWS = MWS;
        this.MSS = MSS;
        this.timeout = timeout;
        this.pdrop = pdrop;
        current_timer = new Timer();
        createSocket();
        this.PTP_send = new PTP(port, IP.toString());
        start_timestamp = System.currentTimeMillis();
        connect();
        send_file_thread().start();
        receive_thread().start();

    }

    public void getLengthOfFile(String path) {
        fileLength = new File(path).length();
    }

    public Thread receive_thread() throws Exception

    {
        return new Thread() {
            public void run() {
                HashMap<String, String> packet_map;
                // receiving the ACK packages
                while (!stop_recv) {

                    try {
                        packet_map = receive();
                        int ACK_number = Integer.parseInt(packet_map.get("ACK_number"));

                        // receving FA ack to close the connection
                        if (PTP.get_flag(packet_map).equals("FA")) {
                            dupACK += dupEnd;
                            disconnect(packet_map);
                            terminate();
                        }
                        // Getting the final acks for the data
                        if (ACK_number >= fileLength) {
                            if (dupEnd == 0) {
                                current_timer.cancel();
                                send_FIN();
                            }
                            dupEnd += 1;

                            continue;
                        }
                        // receiving the expected ack
                        if (ACK_number > startWindow.get() + 1) {
                            // move the base to the current acked location
                            buffer.remove(startWindow.get());
                            startWindow.set(ACK_number - 1);
                            dupACK = 0;

                            current_timer.schedule(createTimerTask(), timeout);
                        } else if (ACK_number == startWindow.get() + 1) {

                            dupACK_overall += 1;
                            dupACK += 1;
                            // fast retransmit and resett dupACK
                            // otherwise we are ignoring the duplicate ACK
                            if (dupACK == 3) {
                                retransmitted_segment += 1;
                                send(getRetransmitPacket());

                                // reschedule the timer again
                                current_timer.schedule(createTimerTask(), timeout);
                                dupACK = 0;
                            }

                        }
                    } catch (Exception e) {
                        System.out.println(e);
                    }

                }
                return;
            }

            public void terminate() {
                stop_recv = true;
            }

        };

    }

    public void send(byte[] send) throws Exception {
        DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
        socket.send(send_packet);

    }

    public HashMap<String, String> receive() throws Exception {

        byte[] receiveData = new byte[1024];
        // receive from server
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        socket.receive(receivePacket);
        HashMap<String, String> packet = PTP.receive_PTP_packet(receivePacket);
        String type = PTP.get_flag(packet);
        log("rcv", type, Integer.parseInt(packet.get("seq_number")), 0, Integer.parseInt(packet.get("ACK_number")));
        return packet;
    }

    public void create_log() throws IOException {
        File file = new File("Sender_log.txt");
        FileWriter fw = new FileWriter(file, true);
        this.log_file = new BufferedWriter(fw);
        log_file.write("flag " + "time " + "type " + "seq number " + "size " + "ack");
        log_file.newLine();
    }

    public void log(String s) throws Exception {
        log_file.write(s);
        log_file.newLine();
    }

    public void log(String srd, String type, Integer seq, Integer size, Integer ACK) throws Exception {

        long time = System.currentTimeMillis() - start_timestamp;
        log_file.write(srd + " " + time + " " + type + " " + seq + " " + size + " " + ACK);
        log_file.newLine();
    }

    public byte[] getRetransmitPacket() {
        String data = buffer.get(startWindow.get());
        return PTP_send.resend_data(data, startWindow.get() + 1);

    }

    public TimerTask createTimerTask() {
        return new TimerTask() {
            public void run() {
                retransmitted_segment += 1;
                byte[] send = getRetransmitPacket();
                DatagramPacket send_packet = new DatagramPacket(send, send.length, receiver_IP, receiver_port);
                try {
                    try {
                        log("snd", "D", startWindow.get(), MSS, PTP_send.last_ACK);
                    } catch (Exception e) {
                        System.out.println(e);
                    }
                    socket.send(send_packet);
                } catch (IOException e) {
                    System.out.println(e);
                }
            }
        };
    }

    public Thread send_file_thread() throws Exception {
        return new Thread() {
            public void run() {
                while (!stop_send) {
                    // current seqNumbsyncLock.lock();er

                    while (nextSeqNumber < fileLength) {

                        int i = nextSeqNumber;
                        while (i < Math.min(MWS + startWindow.get(), fileLength)) {

                            try {
                                int size_to_read = Math.min(MSS, (int) fileLength - i);
                                byte[] data = new byte[size_to_read];
                                if (file.read(data, 0, size_to_read) != 0) {
                                    // reading the data into buffer if needs to be retransmitted
                                    buffer.put(i, new String(data));
                                    if (nextSeqNumber == 0) {
                                        TimerTask task = createTimerTask();
                                        current_timer.schedule(task, timeout);
                                    }
                                    // PL module
                                    if (!PL()) {
                                        log("snd", "D", PTP_send.seq_number, MSS, PTP_send.last_ACK);

                                        num_segments += 1;
                                        send(PTP_send.send_data(data));

                                    } else {
                                        log("drop", "D", PTP_send.seq_number, MSS, PTP_send.last_ACK);
                                        dropped_packet += 1;
                                        PTP_send.drop(data);
                                    }
                                    i += Math.min(fileLength - i, MSS);
                                    nextSeqNumber = i;

                                } else {
                                    return;

                                }
                            } catch (Exception e) {
                                System.out.println(e);
                            }

                        }

                    }
                    terminate();

                }

            }

            public void terminate() {
                stop_send = true;
            }
        };

    }

    void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        socket = new DatagramSocket(null);
        socket.bind(sockaddr);
    }

    public void connect() throws Exception {
        while (true) {
            byte[] sendData = PTP_send.send_SYN();
            send(sendData);

            log("snd", "S", PTP_send.seq_number, 0, PTP_send.last_ACK);
            HashMap<String, String> packet_recieved = receive();

            String result = PTP.get_flag(packet_recieved);
            if (PTP.get_flag(packet_recieved).equals("SA")) {

                sendData = PTP_send.send_ACK(false, false, packet_recieved);
                send(sendData);
                log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
                return;

            }

        }
    }

    public void send_FIN() throws Exception {
        byte[] end = PTP_send.send_FIN();
        send(end);
        log("snd", "F", PTP_send.seq_number, 0, PTP_send.last_ACK);
    }

    public void disconnect(HashMap<String, String> end_ACK) throws Exception {

        byte[] end = PTP_send.send_ACK(false, false, end_ACK);
        send(end);
        socket.close();
        log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
        log("Amount of (original) Data Transferred (in bytes) " + fileLength);
        log("Number of Data Segments Sent (excluding retransmissions) " + num_segments);
        log("Number of (all) Packets Dropped (by the PL module) " + dropped_packet);
        log("Number of Retransmitted Segments " + retransmitted_segment);
        log("Number of Duplicate Acknowledgements received " + dupACK_overall);
        log_file.close();
        file.close();

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

        Sender sender = new Sender(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]),
                Integer.parseInt(args[4]), Integer.parseInt(args[5]), Float.parseFloat(args[6]),
                Integer.parseInt(args[7]));
        return;
    }

}