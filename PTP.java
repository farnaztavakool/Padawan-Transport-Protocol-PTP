import java.net.DatagramPacket;
import java.util.*;

public class PTP {
    /**
     * source port, source IP FIN, SYN, ACK, D seq number ACK - - --- - - Data:
     * ------
     */
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

    public void set_Seq_number(int seq_number) {
        this.seq_number = seq_number;
    }

    // public void set_ACK_number(int ACK_number) {
    // this.ACK_number = ACK_number;
    // }

    public byte[] send_ACK(boolean syn, boolean fin, HashMap<String, String> packet) {
        int data_length = packet.get("Data").equals(" ") ? 0 : packet.get("Data").length();
        int seq = Integer.parseInt(packet.get("seq_number"));
        boolean check = (seq == 1);
        Integer ACK = seq + data_length;
        last_ACK = ACK;
        if (syn == true) {
            seq_number += 1;
            return send_PTP_packet("", "0110", ACK);
        } else if (fin) {
            seq_number += 1;
            return send_PTP_packet("", "1010", ACK);
        }
        return send_PTP_packet("", "0010", ACK);
    }

    public byte[] send_data(byte[] data) {

        return send_PTP_packet(new String(data), "0001", last_ACK);
    }

    // we dont need to set ACK for SYN
    public byte[] send_SYN() {

        seq_number += 1;
        return send_PTP_packet("", "0100", 0);

    }

    public byte[] send_FIN() {
        seq_number += 1;
        return send_PTP_packet("", "1000", 0);

    }
    // public void set_data(string data)

    public byte[] send_PTP_packet(String data, String flag, Integer ACK) {

        String res = IP + " " + port.toString() + " " + flag + " " + this.seq_number.toString() + " " + ACK.toString()
                + " " + "Data " + data;

        seq_number += data.trim().length();
        return res.getBytes();
    }

    public static HashMap<String, String> receive_PTP_packet(DatagramPacket receivedPacket) {
        String data = new String(receivedPacket.getData());
        String[] res = data.split("Data");
        boolean check = res[1].equals("");
        String[] header = res[0].split(" ");
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("IP", header[0]);
        map.put("port", header[1]);
        map.put("flag", header[2]);
        map.put("seq_number", header[3]);
        map.put("ACK_number", header[4]);
        map.put("Data", res[1].trim());
        return map;

    }

    public static String get_flag(HashMap<String, String> packet) {
        switch (packet.get("flag")) {
            case "1000":
                return "FIN";
            case "0100":
                return "SYN";
            case "0010":
                return "ACK";
            case "0110":
                return "SYN/ACK";
            case "1010":
                return "FIN/ACK";
            default:
                return "unknown";
        }
    }

    public boolean ACK(Integer ack) {
        System.out.println(ack + " " + seq_number);
        if (this.seq_number.equals(ack))
            return true;
        return false;
    }

}
