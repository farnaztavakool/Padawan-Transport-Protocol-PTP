import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class PTP {

    private String IP;
    private Integer port;
    public Integer last_ACK;
    public Integer seq_number;

    public PTP(int port, String IP) {
        seq_number = 0;
        last_ACK = 0;
        this.port = port;
        this.IP = IP;
    }

    public void drop(byte[] data) {
        seq_number += (new String(data)).length();
    }

    public void set_Seq_number(int seq_number) {
        this.seq_number = seq_number;
    }

    // when data is out of order
    public byte[] send_last_ack() {
        return send_PTP_packet("", "0010", last_ACK);
    }

    // when there are buffere
    public byte[] send_ACK(int updated_ACK) {
        last_ACK = updated_ACK;
        return send_PTP_packet("", "0010", updated_ACK);
    }

    // this is only used for ACKing SYN, SYN/AK, FIN, FIN/ACK
    public byte[] send_ACK(boolean syn, boolean fin, HashMap<String, String> packet) {
        int seq = Integer.parseInt(packet.get("seq_number"));
        Integer ACK = seq;
        last_ACK = ACK;
        if (syn == true) {
            last_ACK++;
            seq_number += 1;
            return send_PTP_packet("", "0110", ACK++);
        } else if (fin) {
            last_ACK++;
            seq_number += 1;
            return send_PTP_packet("", "1010", ACK++);
        }
        return send_PTP_packet("", "0010", ACK);
    }

    public byte[] send_data(byte[] data) {
        int len = new String(data).length();
        return send_PTP_packet(new String(data), "0001", last_ACK);
    }

    public byte[] resend_data(String data, Integer seq_number) {
        String res = IP + " " + port.toString() + " " + "1111" + " " + seq_number.toString() + " " + last_ACK.toString()
                + " " + "Data " + data + "/";
        return res.getBytes();
    }

    public byte[] send_SYN() {

        seq_number += 1;
        return send_PTP_packet("", "0100", 0);

    }

    public byte[] send_FIN() {
        seq_number += 1;
        return send_PTP_packet("", "1000", 0);

    }

    // Adding header to send the packet
    public byte[] send_PTP_packet(String data, String flag, Integer ACK) {

        String res = IP + " " + port.toString() + " " + flag + " " + this.seq_number.toString() + " " + ACK.toString()
                + " " + "Data " + data + "/";
        if (flag.equals("0001"))
            seq_number += data.length();
        return res.getBytes();
    }

    public static HashMap<String, String> receive_PTP_packet(DatagramPacket receivedPacket) {
        String data = new String(receivedPacket.getData(), StandardCharsets.UTF_8);
        // System.out.println("received " + data);
        String[] res = data.split("Data ");
        String[] header = res[0].split(" ");
        String data_field = "";
        String data_pack = res[1].split("/")[0];

        HashMap<String, String> map = new HashMap<String, String>();
        map.put("IP", header[0]);
        map.put("port", header[1]);
        map.put("flag", header[2]);
        map.put("seq_number", header[3]);
        map.put("ACK_number", header[4]);
        map.put("Data", data_pack);
        return map;

    }

    public static String get_flag(HashMap<String, String> packet) {
        switch (packet.get("flag")) {
            case "1000":
                return "F";
            case "0100":
                return "S";
            case "0010":
                return "A";
            case "0110":
                return "SA";
            case "1010":
                return "FA";
            case "0001":
                return "D";
            case "1111":
                return "retransmit";
            default:
                return "unknown";
        }
    }

    public boolean ACK(Integer ack) {
        if (this.seq_number.equals(ack))
            return true;
        return false;
    }

}
