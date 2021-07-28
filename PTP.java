import java.net.DatagramPacket;
import java.util.*;

public class PTP {
    /**
     * source port, source IP FIN, SYN, ACK, D seq number ACK - - --- - - Data:
     * ------
     */
    private String IP;
    private Integer port;

    private Integer seq_number;
    private Integer ACK_number;
    private String data;

    public PTP(int port, String IP) {
        seq_number = 0;
        ACK_number = 0;
        this.port = port;
        this.IP = IP;
    }

    public void set_Seq_number(int seq_number) {
        this.seq_number = seq_number;
    }

    public void set_ACK_number(int ACK_number) {
        this.ACK_number = ACK_number;
    }

    public byte[] send_ACK(boolean syn) {
        if (syn == true) {
            seq_number += 1;
            return send_PTP_packet("", "0110");
        }
        return send_PTP_packet("", "0010");
    }
    // public void set_data(string data)

    public byte[] send_PTP_packet(String data, String flag) {
        String res = IP + " " + port.toString() + " " + flag + " " + this.seq_number.toString() + " "
                + ACK_number.toString() + " " + "Data " + data;

        return res.getBytes();
    }

    public static HashMap<String, String> receive_PTP_packet(DatagramPacket receivedPacket) {
        String data = new String(receivedPacket.getData());
        String[] res = data.split("Data");
        String[] header = res[0].split(" ");
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("IP", header[0]);
        map.put("port", header[1]);
        map.put("flag", header[2]);
        map.put("seq_number", header[3]);
        map.put("ACK_number", header[4]);
        map.put("Data", header[5]);
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
            default:
                return null;
        }
    }

    public boolean ACK(String ack) {
        if (this.seq_number.equals(ack))
            return true;
        return false;
    }

}
