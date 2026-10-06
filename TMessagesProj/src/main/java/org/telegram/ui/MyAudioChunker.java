package org.telegram.ui;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;

// The voice bot's (tgbot/tasks.py) audio steps without ffmpeg: the recording is decoded once to 16 kHz mono PCM
// with Android's own decoder, then
// - split_on_silence: silences are found like ffmpeg's silencedetect (noise threshold from the mean volume,
//   at least 0.5 s), boundaries chosen with the bot's rules (cut at a silence after 90 s, force a cut after
//   270 s without one, fold a last stretch under 5 s into the previous chunk), each chunk written as WAV;
// - boost_quiet: a loudness-normalized WAV for one retry when the transcript comes back empty.
// WAV is accepted by OpenAI's transcription endpoint, so no encoder is needed.
public class MyAudioChunker {

    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50; // 20 ms analysis frames
    private static final double MAX_CHUNK_SEC = 90;

    private final File dir;
    private final File raw;
    private long samples;
    private double sumSquares;
    private final ArrayList<Float> framePeaksDb = new ArrayList<>();

    public MyAudioChunker(File source, File workDir) throws IOException {
        dir = workDir;
        dir.mkdirs();
        raw = new File(dir, "voice.pcm");
        decode(source);
    }

    public double durationSec() {
        return samples / (double) RATE;
    }

    // Decodes the first audio track to 16 kHz mono 16-bit PCM in a temp file, keeping per-frame peaks.
    private void decode(File source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(source.getAbsolutePath());
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                format = f;
                break;
            }
        }
        if (format == null) {
            extractor.release();
            throw new IOException("no audio track");
        }
        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();
        int inRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        double step = 0; // resampling: input samples are averaged into 16 kHz output samples
        double acc = 0;
        int accCount = 0;
        int framePeak = 0;
        int frameFill = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(raw), 1 << 16)) {
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    inRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (outIndex >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        ShortBuffer pcm = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        double ratio = RATE / (double) inRate;
                        while (pcm.remaining() >= channels) {
                            int mono = 0;
                            for (int c = 0; c < channels; c++) {
                                mono += pcm.get();
                            }
                            acc += mono / (double) channels;
                            accCount++;
                            step += ratio;
                            if (step >= 1) {
                                step -= 1;
                                int sample = (int) Math.max(-32768, Math.min(32767, Math.round(acc / accCount)));
                                acc = 0;
                                accCount = 0;
                                out.write(sample & 0xFF);
                                out.write((sample >> 8) & 0xFF);
                                samples++;
                                sumSquares += (double) sample * sample;
                                framePeak = Math.max(framePeak, Math.abs(sample));
                                if (++frameFill == FRAME) {
                                    framePeaksDb.add(toDb(framePeak));
                                    framePeak = 0;
                                    frameFill = 0;
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            codec.stop();
            codec.release();
            extractor.release();
        }
        if (frameFill > 0) {
            framePeaksDb.add(toDb(framePeak));
        }
    }

    private static float toDb(int peak) {
        return peak <= 0 ? -120f : (float) (20 * Math.log10(peak / 32768.0));
    }

    // adaptive_noise_threshold: the mean volume (ffmpeg volumedetect) minus 15 dB, clamped to [-50, -25].
    private double noiseThreshold() {
        if (samples == 0) {
            return -25;
        }
        double meanDb = 10 * Math.log10(sumSquares / samples / (32768.0 * 32768.0));
        return Math.max(-50.0, Math.min(-25.0, meanDb - 15.0));
    }

    // split_on_silence: chunk boundaries in seconds, then one WAV per chunk.
    public ArrayList<File> splitOnSilence() throws IOException {
        double threshold = noiseThreshold();
        double frameSec = FRAME / (double) RATE;
        int minFrames = (int) Math.ceil(0.5 / frameSec);
        ArrayList<Double> silenceEnds = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < framePeaksDb.size(); i++) {
            if (framePeaksDb.get(i) < threshold) {
                run++;
            } else {
                if (run >= minFrames) {
                    silenceEnds.add(i * frameSec);
                }
                run = 0;
            }
        }
        if (run >= minFrames) {
            silenceEnds.add(framePeaksDb.size() * frameSec);
        }

        double duration = durationSec();
        double hardMax = MAX_CHUNK_SEC * 3;
        ArrayList<Double> boundaries = new ArrayList<>();
        boundaries.add(0.0);
        double last = 0;
        for (double s : silenceEnds) {
            while (s - last > hardMax) {
                last += MAX_CHUNK_SEC;
                boundaries.add(last);
            }
            if (s - last >= MAX_CHUNK_SEC) {
                boundaries.add(s);
                last = s;
            }
        }
        while (duration - last > hardMax) {
            last += MAX_CHUNK_SEC;
            boundaries.add(last);
        }
        if (duration - last < 5 && boundaries.size() > 1) {
            boundaries.set(boundaries.size() - 1, duration);
        } else {
            boundaries.add(duration);
        }

        ArrayList<File> chunks = new ArrayList<>();
        for (int i = 0; i + 1 < boundaries.size(); i++) {
            File chunk = new File(dir, "chunk" + i + ".wav");
            writeWav(chunk, (long) (boundaries.get(i) * RATE), (long) (boundaries.get(i + 1) * RATE), 1.0);
            chunks.add(chunk);
        }
        return chunks;
    }

    // boost_quiet: loudness-normalized copy (about -16 dB RMS, peaks kept under -1.5 dB), or null if too big.
    public File boostQuiet(long maxBytes) throws IOException {
        if (samples == 0 || 44 + samples * 2 > maxBytes) {
            return null;
        }
        double rms = Math.sqrt(sumSquares / samples);
        int peak = 0;
        for (float db : framePeaksDb) {
            peak = Math.max(peak, (int) (32768 * Math.pow(10, db / 20)));
        }
        double gain = rms <= 0 ? 1 : (32768 * Math.pow(10, -16 / 20.0)) / rms;
        if (peak > 0) {
            gain = Math.min(gain, 32768 * Math.pow(10, -1.5 / 20.0) / peak);
        }
        File out = new File(dir, "voice.loud.wav");
        writeWav(out, 0, samples, Math.max(1.0, gain));
        return out;
    }

    private void writeWav(File file, long from, long to, double gain) throws IOException {
        to = Math.min(to, samples);
        long count = Math.max(0, to - from);
        try (RandomAccessFile in = new RandomAccessFile(raw, "r");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes()).putInt((int) (36 + count * 2)).put("WAVE".getBytes());
            header.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
            header.put("data".getBytes()).putInt((int) (count * 2));
            out.write(header.array());
            in.seek(from * 2);
            byte[] buffer = new byte[1 << 15];
            long left = count * 2;
            while (left > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read <= 0) {
                    break;
                }
                if (gain != 1.0) {
                    for (int i = 0; i + 1 < read; i += 2) {
                        int s = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                        s = (int) Math.max(-32768, Math.min(32767, Math.round(s * gain)));
                        buffer[i] = (byte) (s & 0xFF);
                        buffer[i + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                }
                out.write(buffer, 0, read);
                left -= read;
            }
        }
    }

    public void cleanup() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }
}
