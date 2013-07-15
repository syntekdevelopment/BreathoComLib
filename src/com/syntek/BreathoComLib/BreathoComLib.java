/*  Title: BreathoComLib Library
 *  Arthur: Jeff Cheung
 *  Last Updated: 2013-5-9
 *  Version: 1.0
 * 
 * 	Update History
 *  2013-5-9 (v1.0)
 *  - First Released, for Breathometer only.
 * 
 *  Bugs to-be-done
 *  - Audio decoding method update
 */

package com.syntek.BreathoComLib;

import android.content.Context;
import android.media.*;
import android.media.AudioTrack.OnPlaybackPositionUpdateListener;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BreathoComLib is an android library to encode/decode data and communicate between the Breathometer and the Android Device.
 *
 * @author Jeff Cheung from Syntek Development Ltd.
 * @version v1.0 Build 001 (7 July, 2013)
 */
public class BreathoComLib {
    private int bufferSize;
    private AudioRecord audioRecord;

    private RecordPlayThread audioRecThread;

    private volatile int code;
    private boolean bCode0Log;
    private int interceptCnt;
    private int codeBit = 0;
    private int errorCnt = 0;
    private int codeCnt = 0;
    private volatile boolean bRecord, bDecode, bStartAnalyse;

    /**
     * bResponse shows whether the decoding side have any valid response.
     * If bResponse = true, can use fetchResponse() to extract the return data.
     */
    private volatile boolean bResponse;

    private int threshold = 1024;

    private Handler headHandler = new Handler();
    private boolean bHead;

    //private final static int audioSource = MediaRecorder.AudioSource.MIC;
    private final static int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private final static int frequency = 44100;
    private final static int channelConfiguration = AudioFormat.CHANNEL_IN_MONO;
    private final static int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    private final static int poolSamples = frequency / 40;    // 25ms samples for Analyse

    private final static int NONCOHERENT_SAMPLES = 16;
    private final static int TOTALSAMPLES = 5;
    private final static int THRESHOLD_SAMPLES = 32;
    private final static int COHERENT_THRESHOLD = 4096;

    private final static int VERSION = 1;
    private final static int DATECODE = 20130707;

    private final static String PACKAGENAME = "com.syntek.BreathoComLib";

    // Sound Encoding Parameter
    @SuppressWarnings("unused")
    private static final String RIFF_HEADER = "RIFF";
    @SuppressWarnings("unused")
    private static final String WAVE_HEADER = "WAVE";
    @SuppressWarnings("unused")
    private static final String FMT_HEADER = "fmt ";
    @SuppressWarnings("unused")
    private static final String DATA_HEADER = "data";
    private static final int HEADER_SIZE = 44;
    @SuppressWarnings("unused")
    private static final String CHARSET = "ASCII";

    private InputStream inputStream;

    private int format, channels, rate, bits, dataSize;

    private int newRate;
    private int newDataSize;
    private long iDelay = 0;
    private ByteBuffer newWavBuffer;

    private AudioTrack audioTrack;
    private volatile boolean bPlaying;

    private Handler myHandler = new Handler();
    private Handler soundEndHandler = new Handler();

    private AudioManager am;

    private Context context;

    private boolean bLED1, bLED2, bLED1Flash, bLED2Flash;
    private int LED1FlashInterval, LED2FlashInterval;

    private float f1Value[], f2Value[];

    /**
     * Constructor for BreathoComLib.
     *
     * @param <b>Context</b> usually using getBaseContext() in any Activity.
     */
    public BreathoComLib(Context con) {
        // AudioRecord
        bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding);
        audioRecord = new AudioRecord(audioSource, frequency, channelConfiguration, audioEncoding, bufferSize);
        audioRecThread = new RecordPlayThread();

        // SoundEncoder
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, 16, AudioTrack.MODE_STATIC);
        newDataSize = 0;
        soundEndHandler.removeCallbacks(soundEndTimerTask);

        // Setup Context
        context = con;

        bHead = false;
        bRecord = false;
        bDecode = false;

        bLED1 = false;
        bLED2 = false;
        bLED1Flash = false;
        bLED2Flash = false;

        LED1FlashInterval = 0;
        LED2FlashInterval = 0;
    }

    // =====================================================================================
    // 			*** General Method ***
    // =====================================================================================

    /**
     * Get BreathoComLib Version.
     *
     * @return <b>int</b> Current Version
     */
    public int getLibVersion() {
        return VERSION;
    }

    /**
     * Get Library release date.
     *
     * @return <b>int</b> Library release date
     */
    public int getDateCode() {
        return DATECODE;
    }

    /**
     * Check whether any device is plugged into the audio jack.
     *
     * @return <b>boolean</b> true if the audio jack is plugged, else false.
     */
    public boolean getDevicePlugged() {
        am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return am.isWiredHeadsetOn();
    }

    public static int getResourseIdByName(String packageName, String className, String name) {
        Class r = null;
        int id = 0;
        try {
            r = Class.forName(packageName + ".R");

            Class[] classes = r.getClasses();
            Class desireClass = null;

            for (int i = 0; i < classes.length; i++) {
                if(classes[i].getName().split("\\$")[1].equals(className)) {
                    desireClass = classes[i];

                    break;
                }
            }

            if(desireClass != null)
                id = desireClass.getField(name).getInt(desireClass);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }

        return id;
    }

    // =====================================================================================
    // 			*** Sound Recorder ***
    // =====================================================================================

    /**
     * Returns whether the Apps is recording sound from the device.
     *
     * @return <b>boolean</b> true if the App is recording, otherwise false
     */
    public boolean getRecordingState() {
        return bRecord;
    }

    /**
     * Start recording from mic input
     *
     * @return null
     */
    public void startRecording() {
        if (!bRecord) {
            bRecord = true;
            //new RecordPlayThread().start();

            if (audioRecThread == null) {
                audioRecThread = new RecordPlayThread();
                audioRecThread.setPriority(Thread.MAX_PRIORITY);
                audioRecThread.start();
            } else {
                audioRecord.stop();
                audioRecThread.interrupt();
                audioRecThread = new RecordPlayThread();
                audioRecThread.setPriority(Thread.MAX_PRIORITY);
                audioRecThread.start();
            }
        }
    }

    /**
     * Stop reocrding from mic input
     */
    public void stopRecording() {
        bRecord = false;
        audioRecord.stop();
        audioRecThread.interrupt();
    }

    /**
     * Enable decode process
     */
    public void enableDecode() {
        bDecode = true;
    }

    /**
     * Disable decode process
     */
    public void disableDecode() {
        bDecode = false;
    }

    private class RecordPlayThread extends Thread {
        @Override
        public void run() {
            short[] buffer = new short[frequency];
            short[] tempBuf = new short[frequency];
            short[] bufferPool = new short[poolSamples * 2];
            int bufferPoolIndex = 0;
            int tempBufIndex;
            int i, j;

            tempBufIndex = 0;

            bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding);
            audioRecord = new AudioRecord(audioSource, frequency, channelConfiguration, audioEncoding, bufferSize);
            audioRecord.startRecording();

            while (bRecord) {
                int bufferReadSize = audioRecord.read(buffer, 0, bufferSize);
                int startMarker = 0;

                if (bDecode) {
                    // Put real time buffer into temp buffer
                    for (i = 0; i < bufferReadSize; i++) {
                        tempBuf[tempBufIndex] = buffer[i];
                        tempBufIndex++;
                    }

                    if (tempBufIndex > THRESHOLD_SAMPLES) {
                        for (i = 0; i < (tempBufIndex - THRESHOLD_SAMPLES); i++) {
                            if (bStartAnalyse) {
                                bufferPool[bufferPoolIndex] = tempBuf[i];
                                if (++bufferPoolIndex >= (poolSamples)) {
                                    bStartAnalyse = false;
                                    bufferPoolIndex = 0;
                                    if (nonCoherentOperation(bufferPool, 2500, 5000)) {
                                        bResponse = true;
                                        //i = startMarker + 1;
                                        //disableDecode();
                                    } else {
                                        // reverse the counter of buffer Pool for re-calculation of threshold
                                        //i = startMarker + 1;
                                        if (!bResponse)
                                            bResponse = false;
                                    }
                                }
                            } else {
                                // Check if first few samples are over threshold, if over threshold then start Analyse
                                //if (checkThreshold(tempBuf, i)) {
                                if (checkStartLog(tempBuf, 0, i)) {
                                    bStartAnalyse = true;
                                    startMarker = i;
                                    bufferPoolIndex = 0;
                                    bufferPool[bufferPoolIndex] = tempBuf[i];
                                    bufferPoolIndex++;
                                }
                            }
                        }

                        tempBufIndex = 0;

                        for (i = 0; i < THRESHOLD_SAMPLES; i++) {
                            tempBuf[i] = buffer[bufferReadSize - THRESHOLD_SAMPLES + i];
                            tempBufIndex++;
                        }
                    }
                }
            }

            audioRecord.stop();
            //audioRecord.release();
        }
    }

    private boolean nonCoherentOperation(short[] bufferPool, int freq0, int freq1) {
        int i, j;
        float f1CosValue[], f1SinValue[], f2CosValue[], f2SinValue[];

        f1Value = new float[poolSamples];
        f2Value = new float[poolSamples];

        f1CosValue = new float[poolSamples];
        f1SinValue = new float[poolSamples];
        f2CosValue = new float[poolSamples];
        f2SinValue = new float[poolSamples];

        // AGC the peak to peak Value
        boolean bUpTrend, bDownTrend;
        int upTrendPeak, downTrendPeak;

        int startMark;

        bUpTrend = false;
        bDownTrend = false;
        upTrendPeak = 0;
        downTrendPeak = 0;

        startMark = 0;

        for (i = 1; i < poolSamples; i++) {
            if (bufferPool[i] > bufferPool[i - 1]) {
                bUpTrend = true;
                if (bufferPool[i] > upTrendPeak)
                    upTrendPeak = bufferPool[i];
            } else if (bufferPool[i] < bufferPool[i - 1]) {
                bDownTrend = true;
                if (bufferPool[i] < downTrendPeak)
                    downTrendPeak = bufferPool[i];
            }

            // Check Zero-Crossing
            if (bufferPool[i] * bufferPool[i - 1] < 0) {
                if (bUpTrend)
                    if (bDownTrend) {
                        if ((upTrendPeak - downTrendPeak) > threshold) {
                            // Perform AGC
                            int peak;

                            if (Math.abs(upTrendPeak) > Math.abs(downTrendPeak))
                                peak = Math.abs(upTrendPeak);
                            else
                                peak = Math.abs(downTrendPeak);

                            for (j = startMark; j <= i; j++)
                                bufferPool[j] *= 32768 / peak;
                        }
                        startMark = i + 1;
                        bUpTrend = false;
                        bDownTrend = false;
                        downTrendPeak = 0;
                        upTrendPeak = 0;
                    }
            }
        }

        // process the non-coherent operation
        float sampleT1 = 0;
        float stepT1 = (float) ((2) * Math.PI * ((float) freq0 / (float) newRate));
        float sampleT2 = 0;
        float stepT2 = (float) ((2) * Math.PI * ((float) freq1 / (float) newRate));


        for (i = 0; i < (poolSamples - NONCOHERENT_SAMPLES + 1); i++) {
            //sampleT = (float) ((2) * Math.PI * (float) i * ((float) freq0 / (float) newRate));
            sampleT1 += stepT1;
            f1CosValue[i] = (float) bufferPool[i] * (float) Math.cos(sampleT1);
            f1SinValue[i] = (float) bufferPool[i] * (float) Math.sin(sampleT1);

            //sampleT = (float) ((2) * Math.PI * (float) i * ((float) freq1 / (float) newRate));
            sampleT2 += stepT2;
            f2CosValue[i] = (float) bufferPool[i] * (float) Math.cos(sampleT2);
            f2SinValue[i] = (float) bufferPool[i] * (float) Math.sin(sampleT2);
        }

        // summing all the values to get a non-coherent value
        for (i = 0; i < (poolSamples - NONCOHERENT_SAMPLES + 1); i++) {
            float f1SumCosValue, f1SumSinValue;
            float f2SumCosValue, f2SumSinValue;

            f1SumCosValue = 0;
            f1SumSinValue = 0;
            f2SumCosValue = 0;
            f2SumSinValue = 0;

            for (j = 0; j < NONCOHERENT_SAMPLES; j++) {
                f1SumCosValue += f1CosValue[i + j];
                f1SumSinValue += f1SinValue[i + j];
                f2SumCosValue += f2CosValue[i + j];
                f2SumSinValue += f2SinValue[i + j];
            }

            f1SumCosValue /= NONCOHERENT_SAMPLES;
            f1SumSinValue /= NONCOHERENT_SAMPLES;
            f2SumCosValue /= NONCOHERENT_SAMPLES;
            f2SumSinValue /= NONCOHERENT_SAMPLES;

            f1Value[i] = (float) Math.pow(f1SumCosValue, 2) + (float) Math.pow(f1SumSinValue, 2);
            f2Value[i] = (float) Math.pow(f2SumCosValue, 2) + (float) Math.pow(f2SumSinValue, 2);

            f1Value[i] = (float) Math.sqrt(f1Value[i]);
            f2Value[i] = (float) Math.sqrt(f2Value[i]);
        }

        // low pass f1Value & f2Value
        for (i = 0; i < poolSamples - 16; i++) {
            for (j = 1; j < 16; j++) {
                f1Value[i] = f1Value[i] + f1Value[i + j];
                f2Value[i] = f2Value[i] + f2Value[i + j];
            }
            f1Value[i] = (float) (f1Value[i] / 16);
            f2Value[i] = (float) (f2Value[i] / 16);
        }

        // Decode signal and result is put in code variable
        if (decodeSignal(54) == true)
            return true;

        return false;
    }

    private boolean decodeSignal(int bitIntervalSample) {
        int diff[];
        int i, j;
        int startCnt;
        int bit1Cnt, bit0Cnt;

        // Decoding pre-operation
        diff = new int[poolSamples * 2];

        for (i = 0; i < poolSamples; i++) {
            if ((f1Value[i] >= COHERENT_THRESHOLD) || (f2Value[i] >= COHERENT_THRESHOLD)) {
                if (f1Value[i] > f2Value[i])
                    diff[i] = 0;
                else
                    diff[i] = 1;
            } else
                diff[i] = 2;
        }

        // 44 samples for 1ms
        code = 0;
        codeBit = 0x01;

        startCnt = 0;
        do {
            if (diff[startCnt] == 2)
                startCnt++;
        } while ((diff[startCnt] == 2) && (startCnt < poolSamples));

        if (startCnt >= poolSamples)
            return false;

        for (i = 0; i < 15; i++) {
            bit1Cnt = 0;
            bit0Cnt = 0;

            for (j = 0; j < bitIntervalSample; j++) {
                if (diff[(i * bitIntervalSample) + j + startCnt] == 0)
                    bit0Cnt++;
                else if (diff[(i * bitIntervalSample) + j + startCnt] == 1)
                    bit1Cnt++;
            }

            if ((bit0Cnt > (bitIntervalSample / 2) || (bit1Cnt > (bitIntervalSample / 2)))) {
                if (bit1Cnt > bit0Cnt)
                    code = code | codeBit;
            } else
                return false;

            codeBit = codeBit << 1;
        }

        // Header bit check
        if ((code & 0x01) != 0x00)
            return false;

        if ((code & (0x01 << 14)) == 0x00)
            return false;

        code = code & ((0x01 << 14) ^ 0xFFFF);
        code = code >> 1;

        code = HammingDecode(code);

        if (code == 0xFFFF)
            return false;

        return true;
    }

    private int HammingDecode(int data) {
        int i;
        int dataTemp;
        int newData;

        newData = 0;

        if (!CalMask(data, 0x5555))
            return 0xFFFF;
        if (!CalMask(data, 0x6666))
            return 0xFFFF;
        if (!CalMask(data, 0x7878))
            return 0xFFFF;
        if (!CalMask(data, 0x7F80))
            return 0xFFFF;

        // Shift and discard Hamming
        int j = 0;
        int Bit = 0x01;
        for (i = 1; i <= 13; i++) {
            if (i != Math.pow(2, j)) {
                if ((data & 0x01) != 0x00) {
                    newData = newData | Bit;
                }

                Bit = Bit << 1;
            } else {
                j++;
            }

            data = data >> 1;
        }

        return newData;
    }

    private boolean CalMask(int data, int Mask) {
        int OddCnt = 0;
        int dataTemp = data & Mask;
        for (int i = 0; i < 13; i++) {
            if ((dataTemp & 0x01) != 0x00)
                OddCnt++;

            dataTemp = dataTemp >> 1;
        }
        if ((OddCnt & 0x01) == 0x00)
            return false;
        return true;
    }

    /**
     * Return the response status of the App.
     *
     * @return <b>boolean</b> true if there is response from the App
     */
    public boolean getResponseStatus() {
        return bResponse;
    }

    /**
     * Return the code information decoded from the mic input. bResponse will be automatically set to "false" after running fetchResponse().
     *
     * @return <b>int</b> the decoded information from the mic input
     */
    public int fetchResponse() {
        bResponse = false;
        return code;
    }

    private void setThreshold(int v) {
        threshold = v;
    }

    private boolean checkThreshold(short[] tempBuf, int i) {
        float avg = 0;

        for (int x = 0; x < THRESHOLD_SAMPLES; x++)
            avg += (float) Math.abs(tempBuf[i + x]);

        avg /= THRESHOLD_SAMPLES;

        if (avg >= threshold)
            return true;
        else
            return false;
    }

    private boolean checkStartLog(short[] tempBuf, int i, int j) {
        int thresholdCnt = 0;

        for (int x = 0; x < TOTALSAMPLES; x++) {
            if ((tempBuf[(i * 10 + j + x)] > threshold) || (tempBuf[(i * 10 + j + x)] < -threshold))
                thresholdCnt++;
        }

        if (thresholdCnt >= (TOTALSAMPLES - (TOTALSAMPLES / 5)))
            return true;
        else
            return false;
    }

    private boolean checkEndLog(short[] tempBuf, int i, int j) {
        for (int x = 0; x < 10; x++) {
            if ((tempBuf[(i * 10 + j + x)] < threshold) || (tempBuf[(i * 10 + j + x)] < -threshold))
                return true;
        }

        return false;
    }

    private void codeFilling() {
        if (bCode0Log) {
            // Code 0 consecutive count
            if ((interceptCnt >= 12) && (interceptCnt <= 17)) {
                codeBit++;
            }
        } else {
            // Code 1 consecutive count
            if ((interceptCnt >= 5) && (interceptCnt <= 11)) {
                code += (1 << codeBit);
                codeBit++;
            }
        }

        interceptCnt = 0;
    }

    private int startAnalyse(short[] logBuffer) {
        boolean bPosPool;
        boolean b0Series;
        int lvCnt = 0;

        interceptCnt = 0;
        code = 0;
        codeBit = 0;
        errorCnt = 0;
        b0Series = false;

        for (int i = 0; i < (poolSamples); i++) {
            if (logBuffer[i] > 0)
                logBuffer[i] = 1;
            else if (logBuffer[i] < 0)
                logBuffer[i] = -1;
            else
                logBuffer[i] = 0;
        }

        if (logBuffer[0] == 1)
            bPosPool = true;
        else
            bPosPool = false;

        for (int i = 1; i < (poolSamples); i++) {
            if (codeBit >= 10) {
                if ((code & 0x01) != 0)
                    return 0;

                if (((code & 0x200)) == 0)
                    return 0;

                int code1 = code & 0x01E;
                code1 = code1 >> 1;

                int code2 = code & 0x1E0;
                code2 = code2 >> 5;
                code2 = code2 ^ 0x0F;

                if (code1 != code2)
                    return 0;

                // create message which will be send to handler
                codeCnt++;
        /*
                Message msg = Message.obtain(uiHandler);
                msg.obj = Integer.toString(code1);
                uiHandler.sendMessage(msg);
                */

                return i;
            }

            if (bPosPool) {
                if ((logBuffer[i] == 1) || (logBuffer[i] == 0)) {
                    lvCnt++;
                } else {
                    checkLvCnt(lvCnt);
                    bPosPool = false;
                    lvCnt = 1;
                }
            } else {
                if ((logBuffer[i] == -1) || (logBuffer[i] == 0)) {
                    lvCnt++;
                } else {
                    checkLvCnt(lvCnt);
                    bPosPool = true;
                    lvCnt = 1;
                }
            }
        }

        return 0;
    }

    private double getNonCoherentValue(short[] sample, int numSamples, int freq) {
        float cosValue, sinValue;
        float sumCosValue, sumSinValue;
        float sampleT;
        double finalValue;
        int i;

        sumCosValue = 0;
        sumSinValue = 0;

        for (i = 0; i < numSamples; i++) {
            sampleT = (float) ((2) * Math.PI * (float) i * ((float) freq / (float) newRate));
            cosValue = (float) sample[i] * (float) Math.cos(sampleT);
            sinValue = (float) sample[i] * (float) Math.sin(sampleT);

            sumCosValue += cosValue;
            sumSinValue += sinValue;
        }

        sumCosValue = sumCosValue / numSamples;
        sumSinValue = sumSinValue / numSamples;

        sumCosValue = (float) Math.pow(sumCosValue, 2);
        sumSinValue = (float) Math.pow(sumSinValue, 2);

        finalValue = sumCosValue + sumSinValue;
        finalValue = Math.sqrt(finalValue);
        return finalValue;
    }

    private void checkLvCnt(int Cnt) {
        if ((Cnt >= 6) && (Cnt <= 13)) {
            if (bCode0Log) {
                errorCnt = 0;
                interceptCnt++;
                if (interceptCnt >= 14) {
                    codeFilling();
                    interceptCnt = 0;
                }
            } else {
                if (++errorCnt > 2) {
                    codeFilling();
                    bCode0Log = true;
                    interceptCnt = errorCnt + 1;
                    errorCnt = 0;
                }
            }
        }

        if ((Cnt >= 14) && (Cnt <= 20)) {
            if (!bCode0Log) {
                errorCnt = 0;
                interceptCnt++;
                if (interceptCnt >= 8) {
                    codeFilling();
                    interceptCnt = 0;
                }
            } else {
                if (++errorCnt > 2) {
                    codeFilling();
                    bCode0Log = false;
                    interceptCnt = errorCnt + 1;
                    errorCnt = 0;
                }
            }
        }
    }

    // =====================================================================================
    // 			*** Sound Encoder ***
    // =====================================================================================

    /**
     * Encode the parameter data and send to Breathometer through audio output.
     *
     * @param <b>int</b> 1byte data to be transferred.
     */
    public void encodeAndSend(int data) {
        int i;
        int newData = 0;
        int counter = 1;
        int bitMask[] = {0x555, 0x666, 0x878, 0xF80};
        int test;

        /*
        // Bit Stuffing
        for (i=0; i<4 ; i++)
        {
        	test = (int) Math.pow(2, i);
            while (test != counter)
            {
                // shift data to newData
                if ((data & 0x01) == 0x01)
                    newData |= 0x01 << (counter-1);

                data >>= 1;
                counter++;
            }
            
            counter++;
        }
        
        while (counter < 13)
        {
        	 // shift data to newData
            if ((data & 0x01) == 0x01)
                newData |= 0x01 << (counter-1);
            data >>= 1;
            counter++;            
        }

        // Check Parity
        for (i=0; i<4; i++)
        {
        	test = newData & bitMask[i];
            if (checkOddParity(test) == false)
                newData = newData | (0x01 << ((int)Math.pow(2, i) - 1));
        }
        */

        stopBuffer();
        initBuffer();
        if (!bHead) {
            addSilenceDelay(context, 80);
            headHandler.removeCallbacks(headTimer);
            headHandler.postDelayed(headTimer, 100);
        } else {
            headHandler.removeCallbacks(headTimer);
            headHandler.postDelayed(headTimer, 100);
        }

        Code2Wav(context, data, 8);

        setplayRate(44100);
        playBuffer();
        //playBufferStaticLoop(1);
    }

    private Runnable headTimer = new Runnable() {
        public void run() {
            if (!bHead) {
                bHead = true;
                headHandler.removeCallbacks(headTimer);
                headHandler.postDelayed(headTimer, 100);
            } else {
                bHead = false;
                headHandler.removeCallbacks(headTimer);
            }
        }
    };

    private boolean checkOddParity(int d) {
        int counter = 12;
        int parityCnt = 0;

        while (counter != 0) {
            if ((d & 0x01) != 0x00)
                parityCnt++;
            d >>= 1;

            counter--;
        }

        if ((parityCnt & 0x01) == 0x00)
            return false;
        else
            return true;
    }

    private void initBuffer() {
        //myHandler = new Handler();
        newDataSize = 0;
        newWavBuffer.allocate(0);
    }

    private void addWavResource(InputStream stream) {
        ByteBuffer tempBuffer;

        inputStream = stream;

        try {
            verifyHeader(stream);
        } catch (IOException e) {
            //Log.e("Error", "vertifyHeader Error!");
        }

        tempBuffer = ByteBuffer.allocate(0);

        // Prepare old Buffer
        if (newDataSize != 0) {
            tempBuffer = ByteBuffer.allocate(newWavBuffer.capacity());
            tempBuffer = newWavBuffer.duplicate();
        }

        newDataSize += dataSize;

        newWavBuffer = ByteBuffer.allocate(newDataSize);
        newWavBuffer.order(ByteOrder.LITTLE_ENDIAN);

        if ((newDataSize - dataSize) != 0)
            newWavBuffer.put(tempBuffer.array(), 0, tempBuffer.capacity());

        try {
            //stream.skip(44);
            //stream.read(newWavBuffer.array(), 0, dataSize);
            stream.read(newWavBuffer.array(), newDataSize - dataSize, dataSize);
        } catch (IOException e) {
            //Log.e("Error", "Stream Reading Error");
        }
    }

    private Runnable mPlayAudioCodec = new Runnable() {
        @Override
        public void run() {
            stopBuffer();
            playBufferAgain();
        }
    };


    private void playBuffer() {
        bPlaying = true;

        int writeBufferSize = 0;
        int tempDataSize;
        tempDataSize = AudioTrack.getMinBufferSize(newRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (newDataSize > tempDataSize)
            tempDataSize = newDataSize;

        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        audioTrack = new AudioTrack(AudioManager.STREAM_SYSTEM, newRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, tempDataSize, AudioTrack.MODE_STATIC);

        do {
            writeBufferSize += audioTrack.write(newWavBuffer.array(), 0, newDataSize);
        } while (writeBufferSize != newDataSize);

        audioTrack.play();

       /*
        audioTrack.setNotificationMarkerPosition((newDataSize / 2) - 1); // Divided by Channel and 16-bit Encoding/8-bit Encoding in byte form
        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onPeriodicNotification(AudioTrack track) {
                // nothing to do 
            }

            @Override
            public void onMarkerReached(AudioTrack track) {
                Log.i("TAG", "onMarkerReached " + track.getNotificationMarkerPosition());
                track.stop();
                bPlaying = false;
            }
        });
        */
    }

    private void setLoopDelay(long d) {
        iDelay = d;
    }

    private void setplayRate(int rate) {
        newRate = rate;
    }

    /**
     * Return the playing state of the Android Device.
     *
     * @return <b>boolean</b> true if the channel is still playing, else false.
     */
    public boolean getPlayState() {
        int x;

        x = audioTrack.getPlaybackHeadPosition();
        if (x < newDataSize / 2) {
            audioTrack.play();
            return true;
        } else
            return false;

        //return this.bPlaying;
    }

    private void playBufferAgain() {
        audioTrack.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume());
        audioTrack.setNotificationMarkerPosition(newDataSize / 2); // Divided by Channel and 16-bit Encoding/8-bit Encoding in byte form
        audioTrack.setPlaybackPositionUpdateListener(new OnPlaybackPositionUpdateListener() {
            @Override
            public void onPeriodicNotification(AudioTrack track) {
                // nothing to do 
            }

            @Override
            public void onMarkerReached(AudioTrack track) { 
                /*
                Log.d(LOG_TAG, "Audio track end of file reached..."); 
                messageHandler.sendMessage(messageHandler.obtainMessage(PLAYBACK_END_REACHED));
                */
                //stopBuffer();
                audioTrack.stop();
                myHandler.removeCallbacks(mPlayAudioCodec);

                //myHandler.postDelayed(mPlayAudioCodec, 0);//Message will be delivered in 1 second
                myHandler.postDelayed(mPlayAudioCodec, iDelay);
            }
        });

        audioTrack.play();
    }

    private void playBufferStaticLoop(int LoopCnt) {
        int iBufferSize;

        bPlaying = true;
        int minBufferSize = AudioTrack.getMinBufferSize(newRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize > newDataSize)
            iBufferSize = minBufferSize;
        else
            iBufferSize = newDataSize;
        audioTrack = new AudioTrack(AudioManager.STREAM_SYSTEM, newRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, iBufferSize, AudioTrack.MODE_STATIC);
        audioTrack.write(newWavBuffer.array(), 0, iBufferSize);
        audioTrack.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume());

        audioTrack.setLoopPoints(0, iBufferSize / 2, LoopCnt);
        audioTrack.play();
    }

    private void playBufferLoop() {
        int iBufferSize;

        bPlaying = true;
        int minBufferSize = AudioTrack.getMinBufferSize(newRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize > newDataSize)
            iBufferSize = minBufferSize;
        else
            iBufferSize = newDataSize;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, newRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, iBufferSize, AudioTrack.MODE_STREAM);
        audioTrack.write(newWavBuffer.array(), 0, newDataSize);
        audioTrack.setStereoVolume(AudioTrack.getMaxVolume(), AudioTrack.getMaxVolume());

        audioTrack.setNotificationMarkerPosition(newDataSize / 2); // Divided by Channel and 16-bit Encoding/8-bit Encoding in byte form
        audioTrack.setPlaybackPositionUpdateListener(new OnPlaybackPositionUpdateListener() {
            @Override
            public void onPeriodicNotification(AudioTrack track) {
                // nothing to do 
            }

            @Override
            public void onMarkerReached(AudioTrack track) {
                audioTrack.stop();
                myHandler.removeCallbacks(mPlayAudioCodec);

                //myHandler.postDelayed(mPlayAudioCodec, 0);//Message will be delivered in 1 second
                myHandler.postDelayed(mPlayAudioCodec, iDelay);
            }
        });

        audioTrack.play();
    }

    private void stopBuffer() {
        myHandler.removeCallbacks(mPlayAudioCodec);

        if (newDataSize != 0) {
            bPlaying = false;
            audioTrack.stop();
            audioTrack.flush();
        }
    }

    private void stopPlay() {
        bPlaying = false;
        audioTrack.stop();
        audioTrack.flush();
    }

    private void verifyHeader(InputStream wavStream)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        wavStream.read(buffer.array(), buffer.arrayOffset(), buffer.capacity());

        buffer.rewind();
        buffer.position(buffer.position() + 20);
        format = buffer.getShort();
        checkFormat(format == 1, "Unsupported encoding: " + format); // 1 means Linear PCM
        channels = buffer.getShort();
        checkFormat(channels == 1 || channels == 2, "Unsupported channels: " + channels);
        rate = buffer.getInt();
        checkFormat(rate <= 48000 && rate >= 11025, "Unsupported rate: " + rate);
        buffer.position(buffer.position() + 6);
        bits = buffer.getShort();
        checkFormat(bits == 16, "Unsupported bits: " + bits);
        dataSize = 0;
        while (buffer.getInt() != 0x61746164) { // "data" marker
            Log.e("Msg", "Skipping non-data chunk");
            int size = buffer.getInt();
            /*
			wavStream.skip(size);
			buffer.rewind();
			wavStream.read(buffer.array(), buffer.arrayOffset(), 8);
			buffer.rewind();
			*/
        }

        dataSize = buffer.getInt();
        checkFormat(dataSize > 0, "wrong datasize: " + dataSize);

        //return new WavInfo(new FormatSpec(rate, channels == 2), dataSize);
    }

    private void Code2Wav(Context context, int Code, int nBit) {
        addWavHeader(context);

        int bit = 0;
        int CodeTemp;

        while ((nBit > 0) && (bit < nBit)) {
            //CodeTemp = Code >> (nBit-bit-1);
            CodeTemp = Code >> bit;
            if ((CodeTemp & 0x01) != 0x00)
                addWavBit1(context);
            else
                addWavBit0(context);
            bit++;
        }

        addWavHeader(context);
    }

    private void addSilenceDelay(Context context, int D) {
        for (int i = 0; i < (D / 10); i++)
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "wav_silence10ms")));
    }

    private void addWavHeader(Context context) {
        if ((Build.MODEL == "Kindle Fire") && (Build.MANUFACTURER == "Amazon"))
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "kindle_header")));
        else if (Build.MANUFACTURER.toLowerCase().contains("htc"))
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "kindle_header")));
        else
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "wav_header")));
    }

    private void addWavBit1(Context context) {
        if ((Build.MODEL == "Kindle Fire") && (Build.MANUFACTURER == "Amazon"))
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "kindle_bit1")));
        else if (Build.MANUFACTURER.toLowerCase().contains("htc"))
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "kindle_bit1")));
        else
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "bit1")));
    }

    private void addWavBit0(Context context) {
        if ((Build.MODEL == "Kindle Fire") && (Build.MANUFACTURER == "Amazon"))
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "kindle_bit0")));
        else if (Build.MANUFACTURER.toLowerCase().contains("htc"))
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "kindle_bit0")));
        else
            addWavResource(context.getResources().openRawResource(getResourseIdByName(PACKAGENAME, "raw", "bit0")));
    }

    private static void checkFormat(boolean b, String T) {
        if (!b) {
            //Log.e("Error", T);
        }
    }

    private final Runnable soundEndTimerTask = new Runnable() {
        public void run() {

        }
    };

// =====================================================================================
// 			***** Method Specified for BresthoMeter *****
// =====================================================================================
    /*
    public void checkConnect()
    {

    }

    public void lED1On()
    {

    }

    public void lED2On()
    {

    }

    public void lED1Off()
    {

    }

    public void lED2Off()
    {

    }

    public void lED1Flash()
    {

    }

    public void lED2Flash()
    {

    }

    public void setLED1FlashInterval()
    {

    }

    public void setLED2FlashInterval()
    {

    }

    public void sensorOn()
    {

    }

    public void sensorOff()
    {

    }

    public void getSerial0()
    {

    }

    public void getSerial1()
    {

    }

    public void getSerial2()
    {

    }

    public void setSerial0L(byte Num)
    {

    }

    public void setSerial0H(byte Num)
    {

    }

    public void setSerial1L(byte Num)
    {

    }

    public void setSerial1H(byte Num)
    {

    }

    public void setSerial2L(byte Num)
    {

    }

    public void setSerial2H(byte Num)
    {

    }

    public void setCalPt1()
    {

    }

    public void setCalPt2()
    {

    }

    public void getCalPt1()
    {

    }

    public void getCalPt2()
    {

    }

    public void getICVoltage()
    {

    }

    private final Runnable waitICReply = new Runnable() {
        public void run() {

        }
    };
    */
}