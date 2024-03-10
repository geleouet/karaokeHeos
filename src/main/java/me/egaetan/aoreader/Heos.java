package me.egaetan.aoreader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsConnectContext;

public class Heos {

    // https://github.com/juliuscc/heos-api/blob/master/src/connection/discover.ts
    // https://github.com/vmichalak/ssdp-client/blob/master/src/main/java/com/vmichalak/protocol/ssdp/SSDPClient.javaj
    
    public static class Message {
        public HeosMessage heos;
        public Map<String, String> payload;
    }
    
    public static class HeosMessage {
        public String command;
        public String message;
        public String result;
    }
    
    static Consumer<String> publish = __ -> {};
    static ObjectMapper mapper = new ObjectMapper();
    public static void main(String[] args) throws IOException, InterruptedException {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<Device> discover = discover(5_100, "urn:schemas-denon-com:device:ACT-Denon:1");
        discover.forEach(x -> System.out.println(x));
        
        Optional<Device> first = discover.stream().filter(d -> d.serviceType!=null && d.serviceType.contains("urn:schemas-denon-com:device:ACT-Denon:1")).findFirst();
        if (first.isEmpty()) {
            System.out.println("Heos not found");
        }
        Device marrantz = first.get();
        
        start(marrantz);
        
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("src/main/resources/public", Location.EXTERNAL);
        });
        
        app.ws("/api/lyrics", ctx -> {
            CopyOnWriteArrayList<WsConnectContext> list = new CopyOnWriteArrayList<WsConnectContext>();
            ctx.onConnect(h -> {
                System.out.println("Connected");
                list.add(h);
                Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
                    h.sendPing();
                }, 1, 1, TimeUnit.SECONDS);
                publish = l -> {
                    System.out.println("Send");
                    h.send(l);
                };
            });
        });
        
        app.start(8095);
                
        
    }
    
    public static class DeezerCsrf {
        public DeezerCsrfResult results;
    }
    public static class DeezerCsrfResult {
        public String checkForm;
    }

    public static class DeezerSearch {
        public List<DeezerSearchLine> data;
    }
    
    public static class DeezerSearchLine {
        public String id;
    }
    
    public static class Song {
        public String song;
        public String artist;
        public String url;
        public Song(String song, String artist, String url) {
            super();
            this.song = song;
            this.artist = artist;
            this.url = url;
        }
        
    }
    
    
    static ConcurrentMap<String, String> cache = new ConcurrentHashMap<String, String>();
    private static void dispatch(Message message, OutputStream outputStream) {
        switch (message.heos.command) {
        case "event/player_now_playing_changed" : {
            try {
                outputStream.write(("heos://player/get_now_playing_media?"+message.heos.message+"\r\n").getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
            break;
        }
        case "player/get_now_playing_media" : {
            String song = message.payload.get("song");
            String mid = message.payload.get("mid");
            String url = message.payload.get("image_url");
            String artist = message.payload.get("artist");
            System.out.println(song);
            System.out.println(mid);
            
            try {
                publish.accept(mapper.writeValueAsString(new Song(song, artist, url)));
            } catch (JsonProcessingException e) {
            }
            
            if (cache.containsKey(mid)) {
                publish.accept(cache.get(mid));
                break;
            }
            
            HttpClient client = HttpClient.
                    newBuilder()
                    .cookieHandler(new CookieManager())
                    .build();
            {

                HttpRequest req = HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create("https://www.deezer.com/ajax/gw-light.php?method=deezer.getUserData&input=3&api_version=1.0&api_token=&cid=123456"))
                        .build();

                try {
                    HttpResponse<String> rep1 = client.send(req, BodyHandlers.ofString());
                    System.out.println(rep1.statusCode());
                    DeezerCsrf value = mapper.readValue(rep1.body(), DeezerCsrf.class);
                    String apiToken = value.results.checkForm;
                    System.out.println(apiToken);

                    HttpRequest reqLyrics = HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create("https://www.deezer.com/ajax/gw-light.php?method=song.getLyrics&api_version=1.0&api_token="+apiToken+"&cid=&sng_id="  + mid))
                            .build();
                    HttpResponse<String> repLyrics = client.send(reqLyrics, BodyHandlers.ofString());
                    System.out.println(repLyrics.statusCode());
                    String lyrics = repLyrics.body();
                    System.out.println(lyrics);
                    if (lyrics.contains("DATA_ERROR")) {
                        System.out.println("Search alternative Lyrics");
                        HttpRequest searchSong = HttpRequest.newBuilder()
                                .GET()
                                .uri(URI.create("https://api.deezer.com/search?q="+URLEncoder.encode(artist + " " + song, Charset.defaultCharset())))
                                .build();
                        HttpResponse<String> repSearch = client.send(searchSong, BodyHandlers.ofString());
                        if (repSearch.statusCode() == 200) {
                            DeezerSearch search = mapper.readValue(repSearch.body(), DeezerSearch.class);
                            if (search.data != null)
                            for (DeezerSearchLine l : search.data) {
                                reqLyrics = HttpRequest.newBuilder()
                                        .GET()
                                        .uri(URI.create("https://www.deezer.com/ajax/gw-light.php?method=song.getLyrics&api_version=1.0&api_token="+apiToken+"&cid=&sng_id="  + l.id))
                                        .build();
                                repLyrics = client.send(reqLyrics, BodyHandlers.ofString());
                                System.out.println(repLyrics.statusCode());
                                lyrics = repLyrics.body();
                                if (!lyrics.contains("DATA_ERROR")) {
                                    cache.put(mid, lyrics);
                                    publish.accept(lyrics);
                                    System.out.println(lyrics);
                                    break;
                                }
                            }
                            
                        }

                    }
                    else {
                        cache.put(mid, lyrics);
                        publish.accept(lyrics);
                    }

                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }  
            }
            
            break;
        }
        }
    }
    
    private static void start(Device marrantz) throws IOException, UnknownHostException, InterruptedException {
        int defaultPort = 1255;
        Socket socket = SocketFactory.getDefault().createSocket(InetAddress.getByName(marrantz.ip), defaultPort);
        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        
        new Thread(() -> {
            try (Scanner sc = new Scanner(inputStream)) {
                while (sc.hasNext()) {
                    String s = sc.nextLine();
                    System.out.println(s);
                    
                    try {
                        Message message = mapper.readValue(s, Message.class);
                        dispatch(message, outputStream);
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    
                }
                System.out.println("Channel closed");
            }
        }).start();

        Thread.sleep(100);
        outputStream.write("heos://system/register_for_change_events?enable=on\r\n".getBytes());
        outputStream.write("heos://system/heart_beat\r\n".getBytes());
        outputStream.write("heos://system/prettify_json_response?enable=off\r\n".getBytes());
        outputStream.write("heos://browse/get_music_sources\r\n".getBytes());
    }
    
    /**
     * Discover any UPNP device using SSDP (Simple Service Discovery Protocol).
     * @param timeout in milliseconds
     * @param searchTarget if null it use "ssdp:all"
     * @return List of devices discovered
     * @throws IOException
     * @see <a href="https://en.wikipedia.org/wiki/Simple_Service_Discovery_Protocol">SSDP Wikipedia Page</a>
     */
    public static List<Device> discover(int timeout, String searchTarget) throws IOException {
        
        ArrayList<Device> devices = new ArrayList<Device>();
        byte[] sendData;
        byte[] receiveData = new byte[1024];

        /* Create the search request */
        StringBuilder msearch = new StringBuilder("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: ssdp:all\r\nMX: 5\r\nMAN: \"ssdp:discover\"\r\n");
        msearch.append("\r\n");

      

        /* Send the request */
        System.out.println(msearch);
        sendData = msearch.toString().getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName("239.255.255.250"), 1900);
        DatagramSocket clientSocket = new DatagramSocket();
        clientSocket.setBroadcast(true);
        clientSocket.setSoTimeout(timeout);
        clientSocket.send(sendPacket);

        /* Receive all responses */
        while (true) {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                clientSocket.receive(receivePacket);
                Device device = Device.parse(receivePacket);
                if (searchTarget != null && device.serviceType != null && device.serviceType.contains(searchTarget)) {
                    devices.add(device);
                    clientSocket.close();
                    return devices;
                }
                else if (searchTarget == null) {
                    devices.add(device);
                }
            }
            catch (SocketTimeoutException e) { break; }
        }

        clientSocket.close();
        return Collections.unmodifiableList(devices);
    }

    public static Device discoverOne(int timeout, String searchTarget) throws IOException {
        Device device = null;
        byte[] sendData;
        byte[] receiveData = new byte[1024];

        /* Create the search request */
        StringBuilder msearch = new StringBuilder("M-SEARCH * HTTP/1.1\nHost: 239.255.255.250:1900\nMAN: ssdp:discover\n");
        if (searchTarget == null) { msearch.append("ST: ssdp:all\n"); }
        else { msearch.append("ST: ").append(searchTarget).append("\n"); }
        if (timeout >= 1100) { msearch.append("MX: " + Math.floor((timeout-100)/1000) + "\n"); }  // give devices 100ms to respond (in the worst case)
        msearch.append("\r\n");

        /* Send the request */
        sendData = msearch.toString().getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName("239.255.255.250"), 1900);
        DatagramSocket clientSocket = new DatagramSocket();
        clientSocket.setSoTimeout(timeout);
        clientSocket.send(sendPacket);

        /* Receive all responses, but pick first matching... */
        do {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                clientSocket.receive(receivePacket);
                System.out.println("R");
                device = Device.parse(receivePacket);
                if (searchTarget == null || new String(receivePacket.getData()).contains(searchTarget)) {
                }
            }
            catch (SocketTimeoutException e) { break; }
        } while(device == null);

        clientSocket.close();
        return device;
    }

    /**
     * Represent a Device discovered by SSDP.
     */
    static class Device {
        private final String ip;
        private final String descriptionUrl;
        private final String server;
        private final String serviceType;
        private final String usn;

        public Device(String ip, String descriptionUrl, String server, String serviceType, String usn) {
            this.ip = ip;
            this.descriptionUrl = descriptionUrl;
            this.server = server;
            this.serviceType = serviceType;
            this.usn = usn;
        }

        /**
         * Instantiate a new Device Object from a SSDP discovery response packet.
         * @param ssdpResult SSDP Discovery Response packet.
         * @return Device
         */
        public static Device parse(DatagramPacket ssdpResult) {
            HashMap<String, String> headers = new HashMap<String, String>();
            Pattern pattern = Pattern.compile("(.*): (.*)");

            String[] lines = new String(ssdpResult.getData()).split("\r\n");

            for (String line : lines) {
                Matcher matcher = pattern.matcher(line);
                if(matcher.matches()) {
                    headers.put(matcher.group(1).toUpperCase(), matcher.group(2));
                }
            }

            return new Device(
                    ssdpResult.getAddress().getHostAddress(),
                    headers.get("LOCATION"),
                    headers.get("SERVER"),
                    headers.get("ST"),
                    headers.get("USN"));
        }

        public String getIPAddress() {
            return ip;
        }

        public String getDescriptionUrl() {
            return descriptionUrl;
        }

        public String getServer() {
            return server;
        }

        public String getServiceType() {
            return serviceType;
        }

        public String getUSN() {
            return usn;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Device device = (Device) o;

            if (ip != null ? !ip.equals(device.ip) : device.ip != null) return false;
            if (descriptionUrl != null ? !descriptionUrl.equals(device.descriptionUrl) : device.descriptionUrl != null)
                return false;
            if (server != null ? !server.equals(device.server) : device.server != null) return false;
            if (serviceType != null ? !serviceType.equals(device.serviceType) : device.serviceType != null) return false;
            return usn != null ? usn.equals(device.usn) : device.usn == null;

        }

        @Override
        public int hashCode() {
            int result = ip != null ? ip.hashCode() : 0;
            result = 31 * result + (descriptionUrl != null ? descriptionUrl.hashCode() : 0);
            result = 31 * result + (server != null ? server.hashCode() : 0);
            result = 31 * result + (serviceType != null ? serviceType.hashCode() : 0);
            result = 31 * result + (usn != null ? usn.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Device{" +
                    "ip='" + ip + '\'' +
                    ", descriptionUrl='" + descriptionUrl + '\'' +
                    ", server='" + server + '\'' +
                    ", serviceType='" + serviceType + '\'' +
                    ", usn='" + usn + '\'' +
                    '}';
        }
    }
}
