// --8<-- [start:example]
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import org.ngengine.nostr4j.io.NostrRTCPeerConnection;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class StreamExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("send") || args[0].equals("reply"))) {
            throw new IllegalArgumentException("Usage: StreamExample send|reply <connection-id>");
        }
        NGEPlatform.set(new JVMAsyncPlatform());
        String connectionId = args[1];
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        try (NostrRTCPeerConnection connection = new NostrRTCPeerConnection(connectionId)) {
            System.out.println("My peer ID: " + connection.getPeerId().asBech32());
            System.out.println("Paste the other peer's ID:");
            NostrPublicKey remote = NostrPublicKey.fromBech32(console.readLine().trim());
            connection.connect(remote);

            DataInputStream input = new DataInputStream(connection.getInputStream());
            DataOutputStream output = new DataOutputStream(connection.getOutputStream());

            if (args[0].equals("send")) {
                output.writeUTF("Hello from Alice!");
                output.flush();
                System.out.println(input.readUTF());
                output.close();
            } else {
                System.out.println(input.readUTF());
                output.writeUTF("Hello back from Bob!");
                output.flush();
                input.read(); // Wait for Alice's end-of-stream before closing.
            }
        }
    }
}
// --8<-- [end:example]
