package io.github.gauthierhuguenin.atvwake.companion;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

/**
 * Minimal Apple TV Companion client: pair-verify with existing credentials, then listen to
 * SystemStatus / TVSystemStatus events. Ported from pyatv/protocols/companion.
 */
public final class CompanionClient implements Closeable {

    /** SystemStatus values sent by tvOS. */
    public static final int ASLEEP = 1;
    public static final int SCREENSAVER = 2;
    public static final int AWAKE = 3;
    public static final int IDLE = 4;

    public interface Listener {
        void onSystemStatus(int state);
    }

    private static final int PV_START = 5;
    private static final int PV_NEXT = 6;
    private static final int E_OPACK = 8;
    private static final int TAG_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private byte[] outKey;
    private byte[] inKey;
    private long outCounter;
    private long inCounter;
    private long xid = random.nextInt(1 << 16);
    private Listener listener;

    /** Credentials string produced by pyatv: ltpk:ltsk:atv_id:client_id, all hex. */
    public static final class Credentials {
        final byte[] ltpk;
        final byte[] ltsk;
        final byte[] atvId;
        final byte[] clientId;

        public Credentials(String s) {
            String[] parts = s.trim().split(":");
            if (parts.length != 4) throw new IllegalArgumentException("bad credentials");
            ltpk = hex(parts[0]);
            ltsk = hex(parts[1]);
            atvId = hex(parts[2]);
            clientId = hex(parts[3]);
        }
    }

    public void connect(String host, int port, int timeoutMs) throws IOException {
        socket = new Socket();
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** Pair-verify, start a session and subscribe to power state events. */
    public void start(Credentials creds, String rpId, Listener listener) throws IOException {
        this.listener = listener;
        pairVerify(creds);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("_bf", 0);
        info.put("_cf", 512);
        info.put("_clFl", 128);
        // A null "_i" stops tvOS from pushing TVSystemStatus events (see pyatv).
        info.put("_i", rpId);
        info.put("_idsID", creds.clientId);
        info.put("_pubID", "FF:70:79:61:74:76");
        info.put("_sf", 256);
        info.put("_sv", "170.18");
        info.put("model", "iPhone10,6");
        info.put("name", "AppleTV Wake");
        request("_systemInfo", info);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("_srvT", "com.apple.tvremoteservices");
        session.put("_sid", random.nextInt() & 0xFFFFFFFFL);
        request("_sessionStart", session);

        try {
            request("TVRCSessionStart", Collections.singletonMap("ProtocolVersionKey", "1.2"));
        } catch (IOException ignored) {
            // Older tvOS versions do not know this command.
        }

        try {
            Map<?, ?> resp = request("FetchAttentionState", new LinkedHashMap<>());
            Object c = resp.get("_c");
            if (c instanceof Map && ((Map<?, ?>) c).get("state") instanceof Long) {
                listener.onSystemStatus(((Long) ((Map<?, ?>) c).get("state")).intValue());
            }
        } catch (IOException ignored) {
            // Newer tvOS versions answer "No request handler"; pushed events still work.
        }

        event("_interest", Collections.singletonMap("_regEvents",
                Collections.singletonList("SystemStatus")));
        event("_interest", Collections.singletonMap("_regEvents",
                Collections.singletonList("TVSystemStatus")));
    }

    /** Blocks and dispatches events until the connection drops. */
    public void loop() throws IOException {
        socket.setSoTimeout(0);
        while (true) {
            Frame f = readFrame();
            if (f.type == E_OPACK) dispatch(f.payload, -1);
        }
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // --- pair-verify -----------------------------------------------------------------

    private void pairVerify(Credentials creds) throws IOException {
        byte[] priv = new byte[X25519.SCALAR_SIZE];
        X25519.generatePrivateKey(random, priv);
        byte[] pub = new byte[X25519.POINT_SIZE];
        X25519.generatePublicKey(priv, 0, pub, 0);

        Map<Integer, byte[]> tlv = new LinkedHashMap<>();
        tlv.put(Tlv8.SEQ_NO, new byte[] {1});
        tlv.put(Tlv8.PUBLIC_KEY, pub);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("_pd", Tlv8.write(tlv));
        msg.put("_auTy", 4);
        msg.put("_x", xid++);
        sendFrame(PV_START, Opack.pack(msg));

        Map<Integer, byte[]> resp = pairingData(readFrame(PV_NEXT));
        byte[] serverPub = resp.get(Tlv8.PUBLIC_KEY);
        byte[] encrypted = resp.get(Tlv8.ENCRYPTED_DATA);

        byte[] shared = new byte[X25519.POINT_SIZE];
        if (!X25519.calculateAgreement(priv, 0, serverPub, 0, shared, 0)) {
            throw new IOException("bad server key");
        }
        byte[] sessionKey = hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared);

        Map<Integer, byte[]> inner = Tlv8.read(chacha(false, sessionKey, nonce("PV-Msg02"), null, encrypted));
        byte[] identifier = inner.get(Tlv8.IDENTIFIER);
        byte[] signature = inner.get(Tlv8.SIGNATURE);
        if (!Arrays.equals(identifier, creds.atvId)) throw new IOException("unexpected device");
        byte[] signed = concat(serverPub, identifier, pub);
        if (!Ed25519.verify(signature, 0, creds.ltpk, 0, signed, 0, signed.length)) {
            throw new IOException("device signature error");
        }

        byte[] deviceInfo = concat(pub, creds.clientId, serverPub);
        byte[] sig = new byte[Ed25519.SIGNATURE_SIZE];
        Ed25519.sign(creds.ltsk, 0, deviceInfo, 0, deviceInfo.length, sig, 0);
        Map<Integer, byte[]> mine = new LinkedHashMap<>();
        mine.put(Tlv8.IDENTIFIER, creds.clientId);
        mine.put(Tlv8.SIGNATURE, sig);
        byte[] enc = chacha(true, sessionKey, nonce("PV-Msg03"), null, Tlv8.write(mine));

        Map<Integer, byte[]> step3 = new LinkedHashMap<>();
        step3.put(Tlv8.SEQ_NO, new byte[] {3});
        step3.put(Tlv8.ENCRYPTED_DATA, enc);
        Map<String, Object> msg3 = new LinkedHashMap<>();
        msg3.put("_pd", Tlv8.write(step3));
        msg3.put("_x", xid++);
        sendFrame(PV_NEXT, Opack.pack(msg3));
        pairingData(readFrame(PV_NEXT));

        outKey = hkdf("", "ClientEncrypt-main", shared);
        inKey = hkdf("", "ServerEncrypt-main", shared);
        outCounter = 0;
        inCounter = 0;
    }

    private static Map<Integer, byte[]> pairingData(Frame f) throws IOException {
        Object o = Opack.unpack(f.payload);
        if (!(o instanceof Map) || !(((Map<?, ?>) o).get("_pd") instanceof byte[])) {
            throw new IOException("no pairing data");
        }
        Map<Integer, byte[]> tlv = Tlv8.read((byte[]) ((Map<?, ?>) o).get("_pd"));
        if (tlv.containsKey(Tlv8.ERROR)) throw new IOException("pairing error " + tlv.get(Tlv8.ERROR)[0]);
        return tlv;
    }

    // --- messages --------------------------------------------------------------------

    private Map<?, ?> request(String id, Map<String, ?> content) throws IOException {
        long myXid = xid++;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("_i", id);
        msg.put("_t", 2);
        msg.put("_c", content);
        msg.put("_x", myXid);
        sendFrame(E_OPACK, Opack.pack(msg));
        while (true) {
            Frame f = readFrame();
            if (f.type != E_OPACK) continue;
            Map<?, ?> resp = dispatch(f.payload, myXid);
            if (resp != null) {
                if (resp.containsKey("_em")) throw new IOException(id + " failed: " + resp.get("_em"));
                return resp;
            }
        }
    }

    private void event(String id, Map<String, ?> content) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("_i", id);
        msg.put("_t", 1);
        msg.put("_c", content);
        msg.put("_x", xid++);
        sendFrame(E_OPACK, Opack.pack(msg));
    }

    /** Handles an incoming OPACK message; returns it if it is the response to {@code wantedXid}. */
    private Map<?, ?> dispatch(byte[] payload, long wantedXid) {
        Object o = Opack.unpack(payload);
        if (!(o instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) o;
        Object type = m.get("_t");
        if (Long.valueOf(1).equals(type)) {
            Object name = m.get("_i");
            Object c = m.get("_c");
            if (("SystemStatus".equals(name) || "TVSystemStatus".equals(name)) && c instanceof Map) {
                Object state = ((Map<?, ?>) c).get("state");
                if (state instanceof Long && listener != null) {
                    listener.onSystemStatus(((Long) state).intValue());
                }
            }
        } else if (Long.valueOf(3).equals(type) && Long.valueOf(wantedXid).equals(m.get("_x"))) {
            return m;
        }
        return null;
    }

    // --- framing and crypto ----------------------------------------------------------

    private static final class Frame {
        final int type;
        final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private synchronized void sendFrame(int type, byte[] data) throws IOException {
        boolean encrypt = outKey != null && data.length > 0;
        int len = data.length + (encrypt ? TAG_LENGTH : 0);
        byte[] header = {(byte) type, (byte) (len >>> 16), (byte) (len >>> 8), (byte) len};
        if (encrypt) data = chacha(true, outKey, counterNonce(outCounter++), header, data);
        out.write(header);
        out.write(data);
        out.flush();
    }

    private Frame readFrame(int expectedType) throws IOException {
        Frame f = readFrame();
        if (f.type != expectedType) throw new IOException("unexpected frame " + f.type);
        return f;
    }

    private Frame readFrame() throws IOException {
        byte[] header = new byte[4];
        in.readFully(header);
        int len = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] payload = new byte[len];
        in.readFully(payload);
        if (inKey != null && len > 0) payload = chacha(false, inKey, counterNonce(inCounter++), header, payload);
        return new Frame(header[0] & 0xFF, payload);
    }

    private static byte[] chacha(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, byte[] data)
            throws IOException {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(encrypt, new AEADParameters(new KeyParameter(key), TAG_LENGTH * 8, nonce, aad));
        byte[] outBuf = new byte[c.getOutputSize(data.length)];
        int n = c.processBytes(data, 0, data.length, outBuf, 0);
        try {
            n += c.doFinal(outBuf, n);
        } catch (InvalidCipherTextException e) {
            throw new IOException("decryption failed", e);
        }
        return n == outBuf.length ? outBuf : Arrays.copyOf(outBuf, n);
    }

    /** 12-byte nonce: 4 zero bytes followed by an 8-byte ASCII label. */
    private static byte[] nonce(String label) {
        byte[] n = new byte[12];
        byte[] l = label.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(l, 0, n, 12 - l.length, l.length);
        return n;
    }

    /** 12-byte little-endian message counter. */
    private static byte[] counterNonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[i] = (byte) (counter >>> (8 * i));
        return n;
    }

    /** HKDF-SHA512, 32-byte output. An empty salt means HashLen zero bytes (RFC 5869). */
    private static byte[] hkdf(String salt, String info, byte[] ikm) throws IOException {
        try {
            byte[] s = salt.isEmpty() ? new byte[64] : salt.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(s, "HmacSHA512"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA512"));
            mac.update(info.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return Arrays.copyOf(mac.doFinal(), 32);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] r = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, pos, p.length);
            pos += p.length;
        }
        return r;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
