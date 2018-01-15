package Sender;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import Package.Package;

public class FilerSender {
	
	private static boolean fin = false;
	private FileInputStream fis;
	private DatagramSocket sock;
	private final int port = 5000;
	private final int sport = 5001;
	private int positionArray;
	private DatagramPacket backupDataPacket;
	private byte[] toSendFile;
	private SenderState currentState;
	private Transition[][] transi;
	private File file;
	private InetAddress ip;
	private Package backupPacket;
	private boolean ack;
	private int seq;
	
	public FilerSender(String filename, InetAddress ip) {
		currentState = SenderState.START;
		
		transi = new Transition[SenderState.values().length] [SenderMsg.values().length];
		transi[SenderState.START.ordinal()][SenderMsg.set_up_first.ordinal()] = new SetUp();
		transi[SenderState.SEND.ordinal()][SenderMsg.wait_ack.ordinal()] = new WaitAck();
		transi[SenderState.WAIT_FOR_ACK.ordinal()][SenderMsg.ack_true.ordinal()] = new SendNext();
		transi[SenderState.WAIT_FOR_ACK.ordinal()][SenderMsg.ack_false.ordinal()] = new SendRepeat();
		transi[SenderState.WAIT_FOR_ACK.ordinal()][SenderMsg.received_fin.ordinal()] = new End();
		System.out.println("INFO FSM constructed, current state: "+currentState);
		
		this.file = new File(filename);
		this.ip = ip;
		
		try {
			sock = new DatagramSocket(port);
			sock.setSoTimeout(1000);
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}
	
	private void sendPacket(Package pak) throws IOException {
		DatagramPacket dpak = pak.PackageToDatagramPacket();
		backupPacket = pak;
		sendPacket(dpak);
	}
	
	private void sendPacket(DatagramPacket dpak) throws IOException {
		dpak.setAddress(ip);
		dpak.setPort(sport);
		backupDataPacket = dpak;
		sock.send(dpak);
	}
	
	public void processMsg(SenderMsg input){
		System.out.println("INFO Received "+input+" in state "+currentState);
		Transition trans = transi[currentState.ordinal()][input.ordinal()];
		if(trans != null){
			currentState = trans.execute(input);
		}
		System.out.println("INFO State: "+currentState);
	}
	
	private byte[] fileToByteArray() {
		byte[] data = null;
        try {
            fis = new FileInputStream(file);
            data = new byte[(int) file.length()];
            fis.read(data);
            
        } 
        catch (FileNotFoundException e) {
            e.printStackTrace();
        } 
        catch (IOException e) {
            e.printStackTrace();
        } 
        finally {
            try {
                if (fis != null) 
                	fis.close();
            } 
            catch (IOException e) {}
        }
        return data; 
	}
	
	private void prepare(){
		if (ack) {
			if (seq == 0) {
				seq = 1;
			}
			else {
				seq = 0;
			}
		}
		try {
			Package pack = setupPackage();
			sendPacket(pack);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private Package setupPackage() throws IOException {
		byte[] send = splitSendArray();
		if (positionArray >= toSendFile.length) {
			Package pack = new Package(file.getName(), seq, true, true, send);
			return pack;
		}
		else {
			Package pack = new Package(file.getName(), seq, true, false, send);
			return pack;
		}
	}

	private byte[] splitSendArray() {
		byte[] splittedArray;
		if (toSendFile.length - positionArray >= 1000) {
			splittedArray = new byte[1000];
			System.arraycopy(toSendFile, positionArray, splittedArray, 0, 1000);
			positionArray += 1000;	
		}
		else {
			splittedArray = new byte[toSendFile.length - positionArray];
			System.arraycopy(toSendFile, positionArray, splittedArray, 0, toSendFile.length - positionArray);
			positionArray += (toSendFile.length - positionArray);	
		}
		return splittedArray;
	}
	
	private Package waitForIncomingPacket() throws IOException {	
		byte[] buffer = new byte[1400];
		DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);
		sock.receive(incomingPacket);
		Package pak = new Package(incomingPacket);
		return pak;
	}
	
	private void sendBackupPack() {
		try {
			sendPacket(backupDataPacket);
		} catch (IOException e) {
			e.printStackTrace();
		}
}
	
	
	abstract class Transition {
		abstract public SenderState execute(SenderMsg input);
	}
	
	class SetUp extends Transition {
		
		@Override
		public SenderState execute(SenderMsg input) {
			try {
				fis = new FileInputStream(file);
				toSendFile = fileToByteArray();
				prepare();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			return SenderState.WAIT_FOR_ACK;
		}
		
	}
	
	class SendNext extends Transition {
		
		@Override
		public SenderState execute(SenderMsg input) {
				prepare();
			return SenderState.SEND;
		}
	}
	
	class End extends Transition {
		
		@Override
		public SenderState execute(SenderMsg input) {
			fin = true;
			return SenderState.END;
		}
	}
	
	class SendRepeat extends Transition {
		
		@Override
		public SenderState execute(SenderMsg input) {
			sendBackupPack();
			return SenderState.SEND;
		}
	}
	
	class WaitAck extends Transition {
			
			@Override
			public SenderState execute(SenderMsg input) {
				Package incomingPack = null;
				try {
					incomingPack = waitForIncomingPacket();
				} catch (IOException e) {
					System.out.println("Timeout");
					return SenderState.SEND;
				}
				if (incomingPack.getFin()) {
					System.out.println("Fin erhalten");
					processMsg(SenderMsg.received_fin);
					return SenderState.END;
				} 
				else {
					if (incomingPack.getAck() == true) {
						System.out.println("Ack in Ordnung");
						ack = true;
						if (incomingPack.getSeqNum() == seq) {
							System.out.println("Seq in Ordnung");
							long check = incomingPack.getCheckSum();
							Package test = new Package(incomingPack.getFilename(), incomingPack.getSeqNum(), incomingPack.getAck(), incomingPack.getFin(), incomingPack.getContent());
							if (check == test.getCheckSum()) {
								System.out.println("Checksum in Ordnung");
								System.out.println("Package erhalten");
								processMsg(SenderMsg.ack_true);
								return SenderState.SEND;
							}
							else {
								System.out.println("Checksum fehlerhaft");
								processMsg(SenderMsg.ack_false);
								return SenderState.SEND;
							}
						}
						else {
							System.out.println("Seq fehlerhaft");
							processMsg(SenderMsg.ack_false);
							return SenderState.SEND;
						}
					}
					else {
						System.out.println("Ack fehlerhaft");
						processMsg(SenderMsg.ack_false);
						return SenderState.SEND;
					}
				}
				
			}
			
		}

	public static void main (String[] args) throws UnknownHostException {
		FilerSender fs = new FilerSender("default.txt", InetAddress.getByName("127.0.0.1"));
		fs.processMsg(SenderMsg.set_up_first);
	}
}