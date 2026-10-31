package com.findlocally.api.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** Inspects local uploaded bytes only. No remote URLs are passed to native tools. */
@Component
public class MediaInspector {
    public record Result(String state, Integer width, Integer height, Integer duration) {}
    private final String ffprobe, ffmpeg;
    private final int maxVideoSeconds, maxDimension;
    public MediaInspector(@Value("${app.media.ffprobe:ffprobe}") String ffprobe,
            @Value("${app.media.ffmpeg:ffmpeg}") String ffmpeg,
            @Value("${app.media.max-video-seconds:600}") int maxVideoSeconds,
            @Value("${app.media.max-dimension:8192}") int maxDimension) {
        if (ffprobe.isBlank() || ffmpeg.isBlank() || maxVideoSeconds<1 || maxVideoSeconds>86400 || maxDimension<1 || maxDimension>16384)
            throw new IllegalArgumentException("Invalid media inspection limits or executable paths");
        this.ffprobe=ffprobe; this.ffmpeg=ffmpeg; this.maxVideoSeconds=maxVideoSeconds; this.maxDimension=maxDimension;
    }
    public Result inspect(MultipartFile file, boolean document) {
        Path input=null, output=null;
        try {
            if (!signature(file)) return failed();
            if (document && "application/pdf".equalsIgnoreCase(file.getContentType())) return new Result("READY",null,null,null);
            if (file.getContentType().startsWith("image/")) {
                try (var stream=ImageIO.createImageInputStream(file.getInputStream())) {
                    var readers=ImageIO.getImageReaders(stream);
                    if (readers.hasNext()) {
                        var reader=readers.next();
                        try {
                            reader.setInput(stream);
                            int width=reader.getWidth(0), height=reader.getHeight(0);
                            if (!dimensions(width,height)) return failed();
                            if (reader.read(0)==null) return failed();
                            return new Result("READY",width,height,null);
                        } finally { reader.dispose(); }
                    }
                }
            }
            input=Files.createTempFile("findlocally-media-",".bin");
            output=Files.createTempFile("findlocally-probe-",".json");
            try (var bytes=file.getInputStream()) { Files.copy(bytes,input,StandardCopyOption.REPLACE_EXISTING); }
            if (!run(new ProcessBuilder(ffprobe,"-v","error","-protocol_whitelist","file,pipe","-show_streams","-show_format","-of","json",input.toString()),output)) return failed();
            var json=new ObjectMapper().readTree(Files.readString(output));
            var video=json.path("streams").valueStream().filter(n -> "video".equals(n.path("codec_type").asText())).findFirst().orElse(null);
            if (video==null) return failed();
            String codec=video.path("codec_name").asText();
            if (!java.util.Set.of("h264","vp8","vp9","av1","webp").contains(codec)) return failed();
            int width=video.path("width").asInt(), height=video.path("height").asInt();
            if (!dimensions(width,height)) return failed();
            boolean image=file.getContentType().startsWith("image/");
            double duration=image ? 0 : json.path("format").path("duration").asDouble(-1);
            if (!image && (!Double.isFinite(duration) || duration<=0 || duration>maxVideoSeconds)) return failed();
            // Decode the whole bounded file so truncated/invalid streams never become published playback.
            if (!run(new ProcessBuilder(ffmpeg,"-nostdin","-v","error","-xerror","-protocol_whitelist","file,pipe","-i",input.toString(),"-f","null","-"),output)) return failed();
            return new Result("READY",width,height,image ? null : (int)Math.ceil(duration));
        } catch (IOException e) {
            // Missing inspection tools or unreadable input fail closed; owner may replace this upload.
            return failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return failed();
        } finally {
            for (Path p : new Path[]{input,output}) if (p!=null) try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }
    private boolean dimensions(int width,int height) { return width>0 && height>0 && width<=maxDimension && height<=maxDimension && (long)width*height<=40_000_000; }
    private Result failed() { return new Result("FAILED",null,null,null); }
    private boolean signature(MultipartFile file) throws IOException {
        byte[] bytes;
        try(var stream=file.getInputStream()) { bytes=stream.readNBytes(16); }
        String ascii=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
        return switch(file.getContentType().toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg" -> bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255;
            case "image/png" -> bytes.length>=8 && (bytes[0]&255)==137 && ascii.startsWith("PNG",1);
            case "image/webp" -> ascii.startsWith("RIFF") && ascii.startsWith("WEBP",8);
            case "video/mp4" -> ascii.startsWith("ftyp",4);
            case "video/webm" -> bytes.length>=4 && (bytes[0]&255)==26 && (bytes[1]&255)==69 && (bytes[2]&255)==223 && (bytes[3]&255)==163;
            case "application/pdf" -> ascii.startsWith("%PDF-");
            default -> false;
        };
    }
    private boolean run(ProcessBuilder builder,Path output) throws IOException,InterruptedException {
        Process process=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { return process.waitFor(60,TimeUnit.SECONDS) && process.exitValue()==0; }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
