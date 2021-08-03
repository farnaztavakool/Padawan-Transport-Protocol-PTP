import java.util.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.locks.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    AtomicInteger end_flag = new AtomicInteger(0);

    private int nextSeqNumber;
    private Random random;
    private HashMap<Integer, String> buffer;
    static ReentrantLock syncLock = new ReentrantLock();
    public int dupACK;
    public int dupACK_overall;
    public int retransmitted_segment;
    public int dropped_packet;
    public int num_segments;
    private static long fileLength;

    public Sender(String receiver_IP, int receiver_port, String path, int MWS, int MSS, int timeout, float pdrop,
            int seed) throws Exception {
        nextSeqNumber = dupACK = dupACK_overall = retransmitted_segment = num_segments = 0;
        buffer = new HashMap<Integer, String>();
        random = new Random(seed);
        file = new FileInputStream(path);
        getLengthOfFile(path);
        create_log();
        this.IP = InetAddress.getByName("localhost");
        this.port = 3010;
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
        Thread send = send_file_thread();
        Thread end = receive_thread();
        ExecutorService exec = Executors.newCachedThreadPool();
        exec.execute(send);
        exec.execute(end);
        exec.shutdown();

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
                while (true) {
                    if (end_flag.get() == 1)
                        return;

                    try {
                        packet_map = receive();
                        int ACK_number = Integer.parseInt(packet_map.get("ACK_number"));
                        syncLock.lock();
                        System.out.println("in the process of Acing " + ACK_number + " " + startWindow.get());
                        // base packet is Acked and can move forward
                        if (ACK_number >= fileLength) {
                            current_timer.cancel();
                            disconnect();
                            System.out.println("disconnected");
                            try {
                                Thread.currentThread().join();
                            } catch (Exception e) {
                                System.out.println(e);
                            }
                        }
                        if (ACK_number > startWindow.get() + 1) {
                            // move the base to the current acked location
                            buffer.remove(startWindow.get());
                            startWindow.set(ACK_number - 2);
                            dupACK = 0;

                            // reschedule the timer again
                            System.out.println(
                                    "Received Ack for this now cacnelling timer " + ACK_number + " " + startWindow);

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
                                System.out.println("out of order now cacnelling timer and retransmitting" + ACK_number
                                        + " " + startWindow);
                                current_timer.schedule(createTimerTask(), timeout);
                                dupACK = 0;
                            }

                        }
                        syncLock.unlock();
                    } catch (Exception e) {
                        System.out.println(e);
                    }
                    // try {
                    // Thread.sleep(10);
                    // } catch (InterruptedException e) {
                    // System.out.println(e);
                    // }
                }
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
        long cons = 1000;
        long time = (System.currentTimeMillis() - start_timestamp);
        log_file.write(srd + " " + time + " " + type + " " + seq + " " + size + " " + ACK);
        log_file.newLine();
    }

    public byte[] getRetransmitPacket() {
        String data = buffer.get(startWindow.get());
        System.out.println("this is the buffer" + buffer);
        return PTP_send.resend_data(data, startWindow.get() + 1);

    }

    public TimerTask createTimerTask() {
        return new TimerTask() {
            public void run() {
                System.out.println("retransmitting this packet " + startWindow.get());
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

    // I think we only need to manually start the timer for the first read
    // either the timer will timeout and start again or a new file is acked and the
    // timer will start for the new base
    public Thread send_file_thread() throws Exception {
        return new Thread() {
            public void run() {
                while (nextSeqNumber <= fileLength) {
                    // current seqNumbsyncLock.lock();er

                    if (nextSeqNumber < MWS + startWindow.get()) {

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
                                    System.out.println("this is the packet sent " + i);
                                    // System.out.println("this is the packet sent " + startWindow.get());
                                    if (!PL()) {
                                        num_segments += 1;
                                        send(PTP_send.send_data(data));
                                    } else {
                                        dropped_packet += 1;
                                        PTP_send.drop(data);
                                        System.out.println("dropped " + i);
                                    }
                                    i += Math.min(fileLength - i, MSS);
                                    nextSeqNumber = i;

                                    log("snd", "D", PTP_send.seq_number, MSS, PTP_send.last_ACK);

                                } else {
                                    return;

                                }
                            } catch (Exception e) {
                                System.out.println(e);
                            }

                        }
                    }

                }
                try {
                    Thread.currentThread().join();
                } catch (Exception e) {
                    System.out.println(e);
                }
            }
        };
        // while (true) {
        // // current seqNumber
        // if (nextSeqNumber < MWS + startWindow) {

        // int i = nextSeqNumber;
        // while (i < MWS + startWindow) {
        // byte[] data = new byte[MSS + 1];
        // if (file.read(data, 0, MSS) != 0) {
        // // reading the data into buffer if needs to be retransmitted
        // buffer.put(i, new String(data));
        // if (nextSeqNumber == 0) {
        // TimerTask task = createTimerTask();
        // current_timer.schedule(task, timeout);
        // }
        // if (!PL()) {

        // send(PTP_send.send_data(data));
        // }
        // i += Math.min(fileLength - i, MSS);
        // nextSeqNumber = i;
        // log("snd", "D", PTP_send.seq_number, MSS, PTP_send.last_ACK);
        // } else {
        // disconnect();
        // return;
        // }

        // }
        // }
        // try {
        // sleep(100);
        // } catch (InterruptedException e) {
        // System.out.println(e);
        // }

        // }

    }

    void createSocket() throws Exception {

        SocketAddress sockaddr = new InetSocketAddress(IP, port);
        socket = new DatagramSocket(null);
        socket.bind(sockaddr);
    }

    public void connect() throws Exception {
        while (true) {
            System.out.println("Sending SYN");
            byte[] sendData = PTP_send.send_SYN();
            send(sendData);

            log("snd", "S", PTP_send.seq_number, 0, PTP_send.last_ACK);
            System.out.println("logges");
            HashMap<String, String> packet_recieved = receive();

            String result = PTP.get_flag(packet_recieved);
            if (PTP.get_flag(packet_recieved).equals("SA")) {

                System.out.println("receieved SYN/ACK sending ACK");
                sendData = PTP_send.send_ACK(false, false, packet_recieved);
                send(sendData);
                log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
                System.out.println("successfully connected");
                return;

            }

        }
    }

    public void disconnect() throws Exception {
        // sending FIN
        byte[] end = PTP_send.send_FIN();
        send(end);
        log("snd", "F", PTP_send.seq_number, 0, PTP_send.last_ACK);

        HashMap<String, String> end_ACK = receive();
        System.out.println(PTP.get_flag(end_ACK));
        // if the packet is FIN/ACK
        if (PTP.get_flag(end_ACK).equals("FA")) {

            end = PTP_send.send_ACK(false, false, end_ACK);
            send(end);
            socket.close();
            log("snd", "A", PTP_send.seq_number, 0, PTP_send.last_ACK);
            log("Amount of (original) Data Transferred (in bytes) " + fileLength);
            log("Number of Data Segments Sent (excluding retransmissions) " + num_segments);
            log("Number of (all) Packets Dropped (by the PL module) " + dropped_packet);
            log("Number of Retransmitted Segments " + retransmitted_segment);
            log("Number of Duplicate Acknowledgements received " + dupACK_overall);
            log_file.close();
            end_flag.set(1);

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