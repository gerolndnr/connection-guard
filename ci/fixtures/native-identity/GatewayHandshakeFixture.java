package fixture;

import org.geysermc.floodgate.crypto.*;
import org.geysermc.floodgate.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Uses the owned isolated Floodgate key to send synthetic gateway data, not an authenticated account. */
public final class GatewayHandshakeFixture {
    public static void main(String[] args) throws Exception {
        if (args.length!=3 || !(args[2].equals("plain")||args[2].equals("linked")||args[2].equals("corrupt"))) throw new IllegalArgumentException("Fixture arguments");
        byte[] key=Files.readAllBytes(Paths.get(args[0]));
        if (key.length!=16) throw new IllegalArgumentException("Unexpected owned gateway key length");
        AesCipher cipher=new AesCipher(new Base64Topping());cipher.init(new AesKeyProducer().produceFrom(key));
        UUID bedrockId=UUID.fromString("00000000-0000-0000-0000-000000000123");
        LinkedPlayer linked=args[2].equals("linked")?LinkedPlayer.of("CGGatewayLinked",UUID.fromString("dddddddd-1111-4222-8333-444444444444"),bedrockId):null;
        BedrockData data=BedrockData.of("1.21.11","CGGateway","291",1,"en_US",0,1,"127.0.0.1",linked,linked!=null,-1,"fixture");
        byte[] encrypted=cipher.encryptFromString(data.toString());
        if (args[2].equals("corrupt")) encrypted[encrypted.length-3]=encrypted[encrypted.length-3]=='A'?(byte)'B':(byte)'A';
        byte[] hostname=("localhost\0"+new String(encrypted,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        Files.write(Paths.get(args[1]),hostname,StandardOpenOption.CREATE_NEW);
    }
}
