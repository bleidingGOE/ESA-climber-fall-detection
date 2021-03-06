package com.lha.falldetection;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.Toast;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.widget.TextView;
import android.os.PowerManager.WakeLock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.w3c.dom.Text;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    DatabaseHelper myDB;
    private static String dbName;
    DatabaseHelper myAltitudeDB;
    DatabaseHelper myKalmanAltitudeDB;
    DatabaseHelper myAccDB;
    public static boolean firstCall;


    private TextView xText, yText, zText, altitudeText;
    private Button mTapme;
    private Sensor mySensor;
    private Sensor pressureSensor;
    private SensorManager SM;
    private boolean btnStartStop = false;

    protected static double threshold = 2;
    protected static double fallDuration = 0;
    protected static double fallenDistance = 0;
    protected static double timerDuration = 1;
    protected static double distanceToAlarm = 0.1;
    protected static int fallDetectionAlgo = 1; //Checks which algorithm should be selected to detect fall events, by default algorithm 1 is used

    public Toast myToast;
    private Handler mHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            Toast handlerToast = Toast.makeText(getApplicationContext(), "", Toast.LENGTH_SHORT);
            handlerToast.setText("DB insert finished!");
            handlerToast.show();
        }
    };


    private boolean fallDetectedFlag = false;
    private boolean nofallDetectedFlag = false;
    private boolean fallFinished = false;
    private Long fallDetectedTS = Long.valueOf(0);
    private Long nofallDetectedTS = Long.valueOf(0);
    private Long firstNoFallTS = Long.valueOf(0);
    private Long rudeAccMovementTS = Long.valueOf(0);

    private WakeLock wakeLock;

    private float altiAvrgAltitude = 0;
    private float altiPrevAvrgAltitude = 0;
    private int altiInitCounter = 0; // First altitude sensor readings are very noisy
    private int altiCounter = 0;
    private float altiDiff = 0;
    private float currentAltitude = 0;
    private float rudeMvmtAltitude = 0;
    private float altiSum = 0;
    private boolean decreased = false;
    private float highestAltitude = 0;
    private float lowestAltitude = 0;

    private int counter = 0;                                        //Counter used for 2nd algorithm to seperate sample blocks
    public static double threshold_DetectingFallEvents = 0.4;    //This threshold is used to set what fall events should be detected in meters. E.g. 1 is only considers fall events that are bigger than 1 meter
    public static double sampleRate = 5;                      //Sets Sample Rate at which the data is scanned from beginning to end. E.g. 30 means Blocks of 30 samples are compared step by step
    public static double timeDifferenceBetweenFallEvents = 5; //Sets the minimal time difference between detected falls to prevent double detection in checking for validFallevents
    public static List <Double> detectedFallEventsTimestamps = new ArrayList();
    private double prevDetectedFall_CounterFlag = 0;
    private double sum = 0;
    private double currentAverageAltitude = 0;
    private double previousAverageAltitude = 0;
    private double highestAltitudeAlgo2 = 0;
    private double lowestAltitudeAlgo2 = 0;

    private List<Float> listOfAltitudes = new ArrayList<Float>();
    private List<Float> listOfKalmanAltitudes = new ArrayList<Float>();
    private List<Long> altitudeTimeStamps = new ArrayList<Long>();
    private List<Long> altitudeTimeStampsNano = new ArrayList<Long>();
    private List<Float> accelerationMeans = new ArrayList<Float>();
    private List<Long> accelerationTimeStamps = new ArrayList<Long>();
    private List<Long> accelerationTimeStampsNano = new ArrayList<Long>();


    // kalman variables for altitude
    float varVolt = 0.010627865f; // variance determined by using excel and raw sensor data
    float varProcess = 1e-6f;
    float Pc = 0.0f;
    float G = 0.0f;
    float P = 1.0f;
    float Xp = 0.0f;
    float Zp = 0.0f;
    float Xe = 0.0f;


    // kalman variables for averaged acc BUT ACC is only used to detect rude/rough movements
    float varVoltACC = 1.12184278324081E-05f;  // variance determined by using excel and raw sensor data
    float varProcessACC = 1e-8f;
    float PcACC = 0.0f;
    float GACC = 0.0f;
    float PACC = 1.0f;
    float XpACC = 0.0f;
    float ZpACC = 0.0f;
    float XeACC = 0.0f;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "falldetector:WakelockTag");


        myToast = Toast.makeText(getApplicationContext(), "", Toast.LENGTH_SHORT);

        //Create our Sensor Manager

        SM = (SensorManager)getSystemService(SENSOR_SERVICE);

        //ACCELEROMETER SENSOR

        mySensor = SM.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        pressureSensor = SM.getDefaultSensor(Sensor.TYPE_PRESSURE);



        //Assign TextView
        xText = (TextView)findViewById(R.id.xText);
        yText = (TextView)findViewById(R.id.yText);
        zText = (TextView)findViewById(R.id.zText);
        altitudeText = (TextView)findViewById(R.id.altitudeText);
        mTapme = (Button)findViewById(R.id.button);


        fallDetectedFlag = false;



        Bundle extras = getIntent().getExtras();
        System.out.println("boolean firstCall: " + firstCall);
        System.out.println("myDB.DATABASENAME: " + myDB.DATABASE_NAME);
        if (extras != null) {
            dbName = extras.getString("selectedDBName");
            System.out.println("Set dbName in MainAcitivity wit INTENT: " + dbName);

            myDB = new DatabaseHelper(this, dbName);
            //The key argument here must match that used in the other activity
        } else if(firstCall){
            dbName = createDBName();
            System.out.println("Set dbName in MainAcitivity with createDBName: " + dbName);
            myDB = new DatabaseHelper(this, dbName);
        } else {
            dbName = myDB.DATABASE_NAME;
            System.out.println("Set dbName in MainAcitivity with last else: " + dbName);
            myDB = new DatabaseHelper(this, dbName);
        }


    }

    private String createDBName() {
        firstCall = false;
        try {
            File root = this.getFilesDir();
            File gpxfile = new File(root, "databaseNames.txt");
            if (gpxfile.exists()) {
                FileInputStream is = new FileInputStream(gpxfile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line = reader.readLine();
                String [] firstLineArray = line.split(",");
                for(int i = 0; i < firstLineArray.length; i++) {
                    System.out.println("line db Item: " + i + ". : " + firstLineArray[i]);
                }
                String lastDBName = firstLineArray[firstLineArray.length - 1];
                System.out.println("Length of Line Array: " + firstLineArray.length);
                System.out.println("databaseNames: " +  line);
                System.out.println("lastDBName: " +  lastDBName);
                return lastDBName;
            } else{
                Date cDate = new Date();
                String fDate = new SimpleDateFormat("yyyy-MM-dd").format(cDate);
                String dbName = "Fall_1_" + fDate + ".db";
                //String dbName = "Fall_1_2018-07-23.db";
                System.out.println("dbName: " +  dbName);

                try {
                    File dbNameDir = this.getFilesDir();
                    if (!dbNameDir.exists()) {
                        dbNameDir.mkdirs();
                    }
                    File dbNameFile = new File(dbNameDir, "databaseNames.txt");
                    if (!gpxfile.exists()) {
                        gpxfile.createNewFile();
                    }
                    FileWriter writer = new FileWriter(gpxfile, true);
                    writer.append(dbName + ",");
                    writer.flush();
                    writer.close();
                    System.out.println("Before toast in txt generator");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return dbName;
            }


        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }


    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        //Select the first algorithm to detect fall events
        if(fallDetectionAlgo == 1) {
            double altitude = 0;
            if(sensorEvent.sensor.getType() == 6){
                float alpha = 0.2f;

                System.out.println("Altitude Pressure: " + sensorEvent.values[0]);
                System.out.println("Altitude Atmosphere Pressure: " + SensorManager.PRESSURE_STANDARD_ATMOSPHERE);
                System.out.println("Altitude: " + SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (alpha * sensorEvent.values[0] + ((float)1 - alpha) * sensorEvent.values[0])));
                altitudeText.setText("Altitude: " + SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (alpha * sensorEvent.values[0] + ((float)1 - alpha) * sensorEvent.values[0])));
                currentAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (alpha * sensorEvent.values[0] + ((float)1 - alpha) * sensorEvent.values[0]));


                Pc = P + varProcess;
                G = Pc/(Pc + varVolt);    // kalman gain
                P = (1-G)*Pc;
                Xp = Xe;
                Zp = Xp;
                Xe = G*(currentAltitude - Zp) + Xp;   // the kalman estimate of the sensor voltage
                //System.out.println(sensorReadings[i]);
                System.out.println("Kalman Filtered: " + Xe);
                altitudeText.setText("Altitude: " + Xe);

                listOfAltitudes.add(currentAltitude);
                listOfKalmanAltitudes.add(Xe);
                altitudeTimeStamps.add(System.currentTimeMillis());
                altitudeTimeStampsNano.add(System.nanoTime());

                if (listOfKalmanAltitudes.size() > 3) {
                    if (Xe > listOfKalmanAltitudes.get(listOfKalmanAltitudes.size() - 2)) {
                        highestAltitude = Xe;
                        mTapme.setBackgroundColor(Color.GREEN);
                        System.out.println("Device is rising.");
                        //fallFinished = false;
                    } else {

                        mTapme.setBackgroundColor(Color.RED);
                        System.out.println("Device is dropping.");
                        if (fallFinished) {
                            altiDiff = (highestAltitude - Xe);
                            System.out.println("Altitude Difference in altimeter: " + altiDiff);
                        }
                    }
                }



            }

            if(btnStartStop && sensorEvent.sensor.getType() == 1){
                xText.setText("X: " + sensorEvent.values[0]);
                yText.setText("Y: " + sensorEvent.values[1]);
                zText.setText("Z: " + sensorEvent.values[2]);

                float x, y, z;
                x = sensorEvent.values[0];
                y = sensorEvent.values[1];
                z = sensorEvent.values[2];
                float mean = (Math.abs(x) + Math.abs(y) + Math.abs(z)) / 3; //(x + y + z) / 3;//
                System.out.println("Acc Mean: " + mean);
                accelerationMeans.add(mean);

                double timeDiffNowAndRudeMvmt = (double)(System.currentTimeMillis() - rudeAccMovementTS) / 1000;
                double timeDiffNowAndFallDetected = (double)(System.currentTimeMillis() - fallDetectedTS) / 1000;
                System.out.println("Time Difference from Now to last rude movement: " + timeDiffNowAndRudeMvmt);
                System.out.println("Time Difference from Now to detected fall: " + timeDiffNowAndFallDetected);

                if (rudeMvmtAltitude != 0 && timeDiffNowAndRudeMvmt > 1) {
                    fallenDistance = rudeMvmtAltitude - currentAltitude;
                    fallDuration = fallDuration = calculateFallDuration(fallenDistance);
                    System.out.println("fallen distance calculated by barometer: " + fallenDistance);

                    if(fallenDistance >= distanceToAlarm ) {

                        myToast.setText("Fall Detected");
                        myToast.show();
                        myDB.insertData(fallenDistance, (double) currentAltitude, fallDuration, System.currentTimeMillis());
                        System.out.println("DATABASENAME: " + myDB.DATABASE_NAME + ", fall inserted in DB");
                        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                        Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
                        r.play();
                        //Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                        //Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
                        //r.play();
                    }
                    rudeMvmtAltitude = 0;
                }

                if (mean > 7.5 && timeDiffNowAndRudeMvmt > 1) {
                    rudeMvmtAltitude = currentAltitude;
                    rudeAccMovementTS = System.currentTimeMillis();
                    myToast.setText("Scanning for Fall Events");
                    myToast.show();
                    System.out.println("Current Acc is too high. Rude movement detected!");



                    fallDetectedTS = Long.valueOf(0);

                }


                accelerationTimeStamps.add(System.currentTimeMillis());
                accelerationTimeStampsNano.add(System.nanoTime());

                if(!fallDetectedFlag) {
                    if (x <= threshold && x >= -threshold && y <= threshold && y >= -threshold && z <= threshold && z >= -threshold) {
                        //btnStartStop = false;
                        System.out.println("currentTime in millis: " + System.currentTimeMillis());
                        System.out.println("firstNoFall in millis: " + firstNoFallTS);
                        System.out.println("Time difference firstNoFall to current: " + (System.currentTimeMillis() - firstNoFallTS));
                        System.out.println("First Detection => x: " + x + "y: " + y + "z: " + z);
                        if(!(((System.currentTimeMillis() - firstNoFallTS) / 1000) < 0.5)) {
                            try {
                                //myToast.setText("Fall Detected");
                                System.out.println("Fall Detected");
                                //myToast.show();
                                fallFinished = false;
                                fallDetectedTS = System.currentTimeMillis();
                                fallDetectedFlag = true;
                                nofallDetectedFlag = false;
                                highestAltitude = Xe;


                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else {
                    if (x <= threshold && x >= -threshold && y <= threshold && y >= -threshold && z <= threshold && z >= -threshold) {
                        //Still falling
                        System.out.println("Still Falling => x: " + x + "y: " + y + "z: " + z);
                    } else {
                        if(!nofallDetectedFlag){

                            nofallDetectedTS = System.currentTimeMillis();
                            firstNoFallTS = nofallDetectedTS;
                            nofallDetectedFlag = true;
                            fallDetectedFlag = false;

                        }
                    }
                }


            }
        } else if(fallDetectionAlgo == 2) {

            if(sensorEvent.sensor.getType() == 6){
                float alpha = 0.2f;

                System.out.println("Altitude Pressure: " + sensorEvent.values[0]);
                System.out.println("Altitude Atmosphere Pressure: " + SensorManager.PRESSURE_STANDARD_ATMOSPHERE);
                System.out.println("Altitude: " + SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (alpha * sensorEvent.values[0] + ((float)1 - alpha) * sensorEvent.values[0])));
                altitudeText.setText("Altitude: " + SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (alpha * sensorEvent.values[0] + ((float)1 - alpha) * sensorEvent.values[0])));
                currentAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (alpha * sensorEvent.values[0] + ((float)1 - alpha) * sensorEvent.values[0]));


                Pc = P + varProcess;
                G = Pc/(Pc + varVolt);    // kalman gain
                P = (1-G)*Pc;
                Xp = Xe;
                Zp = Xp;
                Xe = G*(currentAltitude - Zp) + Xp;   // the kalman estimate of the sensor voltage
                //System.out.println(sensorReadings[i]);
                System.out.println("Kalman Filtered: " + Xe);
                altitudeText.setText("Altitude: " + Xe);

                listOfAltitudes.add(currentAltitude);
                listOfKalmanAltitudes.add(Xe);
                altitudeTimeStamps.add(System.currentTimeMillis());
                altitudeTimeStampsNano.add(System.nanoTime());

                if (listOfKalmanAltitudes.size() > 3) {
                    if (Xe > listOfKalmanAltitudes.get(listOfKalmanAltitudes.size() - 2)) {
                        highestAltitude = Xe;
                        mTapme.setBackgroundColor(Color.GREEN);
                        System.out.println("Device is rising.");
                        //fallFinished = false;
                    } else {

                        mTapme.setBackgroundColor(Color.RED);
                        System.out.println("Device is dropping.");
                        if (fallFinished) {
                            altiDiff = (highestAltitude - Xe);
                            System.out.println("Altitude Difference in altimeter: " + altiDiff);
                        }
                    }
                }

                if (counter != 0 && counter % sampleRate == 0) {
                    currentAverageAltitude = sum / sampleRate;
                    //System.out.println("Counter: " + counter);
                    //System.out.println("unfilteredAltitudes: " + unfilteredAltitudes.get(counter));
                    //System.out.println("highestAltitude: " + highestAltitude);
                    //System.out.println("lowestAltitude: " + lowestAltitude);
                    //System.out.println("currentAverageAltitude: " + currentAverageAltitude);
                    sum = 0;

                    //The next 30th value has to be put here, otherwise it will not be considered
                    sum += currentAltitude;

                    if (previousAverageAltitude == 0) {
                        previousAverageAltitude = currentAverageAltitude;
                        highestAltitudeAlgo2 = currentAverageAltitude;
                        lowestAltitudeAlgo2 = currentAverageAltitude;
                    }

                    if (currentAverageAltitude <= previousAverageAltitude) {

                        if (previousAverageAltitude > highestAltitudeAlgo2) {
                            highestAltitudeAlgo2 = previousAverageAltitude;
                        }

                        if (currentAverageAltitude < lowestAltitudeAlgo2) {
                            lowestAltitudeAlgo2 = currentAverageAltitude;
                        }

                        previousAverageAltitude = currentAverageAltitude;


                    } else if(currentAverageAltitude > previousAverageAltitude) {

                        if (currentAverageAltitude > highestAltitudeAlgo2) {
                            highestAltitudeAlgo2 = currentAverageAltitude;
                        }

                        previousAverageAltitude = currentAverageAltitude;

                        double altitudeDifference = highestAltitudeAlgo2 - lowestAltitudeAlgo2;
                        //System.out.println("highestAltitude: " + highestAltitude);
                        //System.out.println("lowestAltitude: " + lowestAltitude);

                        if (altitudeDifference > threshold_DetectingFallEvents) {

                            if (prevDetectedFall_CounterFlag == 0) {

                                prevDetectedFall_CounterFlag = currentAltitude;
                                double time = (double)altitudeTimeStamps.get(altitudeTimeStamps.size() - 1);
                                detectedFallEventsTimestamps.add(time);
                                double alti = currentAltitude;
                                //System.out.println(highestAltitude - unfilteredAltitudes.get(counter));
                                System.out.println("Fall Detected!");
                                System.out.printf("%.0f\t%.12f\n", time, alti);
                                fallenDistance = altitudeDifference;
                                fallDuration = calculateFallDuration(fallenDistance);

                                if(altitudeDifference > distanceToAlarm) {
                                    myToast.setText("Fall Detected");
                                    myToast.show();
                                    myDB.insertData(altitudeDifference, (double) currentAltitude, fallDuration, altitudeTimeStamps.get(altitudeTimeStamps.size() - 1));
                                    System.out.println("DATABASENAME: " + myDB.DATABASE_NAME + ", fall inserted in DB");
                                    Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                                    Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
                                    r.play();
                                }


                            } else if ( isValidFallEvent((double)altitudeTimeStamps.get(altitudeTimeStamps.size() - 1) ) ) {
                                prevDetectedFall_CounterFlag = currentAltitude;
                                //System.out.println("Fall Detected ID: " + (counter + 1) + "; Timestamp: " + unfilteredTimestamps.get(counter) + "; Altitude: " + unfilteredAltitudes.get(counter));
                                //System.out.println(counter);
                                //System.out.println(unfilteredTimestamps.get(counter) + "\t" + unfilteredAltitudes.get(counter));
                                double time = (double)altitudeTimeStamps.get(altitudeTimeStamps.size() - 1);
                                detectedFallEventsTimestamps.add(time);
                                double alti = currentAltitude;
                                //System.out.println(highestAltitude - unfilteredAltitudes.get(counter));
                                System.out.println("Fall Detected!");
                                System.out.printf("%.0f\t%.12f\n", time, alti);
                                fallenDistance = altitudeDifference;
                                fallDuration = calculateFallDuration(fallenDistance);

                                if(altitudeDifference > distanceToAlarm) {
                                    myToast.setText("Fall Detected");
                                    myToast.show();
                                    myDB.insertData(altitudeDifference, (double) currentAltitude, fallDuration, altitudeTimeStamps.get(altitudeTimeStamps.size() - 1));
                                    System.out.println("DATABASENAME: " + myDB.DATABASE_NAME + ", fall inserted in DB");
                                    Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                                    Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
                                    r.play();
                                }
                            }




                        }

                        previousAverageAltitude = 0;
                        highestAltitudeAlgo2 = 0;
                        lowestAltitudeAlgo2 = 0;



                    }

                } else {
                    sum += currentAltitude;
                }

                counter++;



            }

            if(btnStartStop && sensorEvent.sensor.getType() == 1) {
                xText.setText("X: " + sensorEvent.values[0]);
                yText.setText("Y: " + sensorEvent.values[1]);
                zText.setText("Z: " + sensorEvent.values[2]);

                float x, y, z;
                x = sensorEvent.values[0];
                y = sensorEvent.values[1];
                z = sensorEvent.values[2];
                float mean = (Math.abs(x) + Math.abs(y) + Math.abs(z)) / 3; //(x + y + z) / 3;//
                System.out.println("Acc Mean: " + mean);
                accelerationMeans.add(mean);

                double timeDiffNowAndRudeMvmt = (double) (System.currentTimeMillis() - rudeAccMovementTS) / 1000;
                double timeDiffNowAndFallDetected = (double) (System.currentTimeMillis() - fallDetectedTS) / 1000;
                System.out.println("Time Difference from Now to last rude movement: " + timeDiffNowAndRudeMvmt);
                System.out.println("Time Difference from Now to detected fall: " + timeDiffNowAndFallDetected);

                if (mean > 7.5 && timeDiffNowAndRudeMvmt > 1) {
                    rudeMvmtAltitude = currentAltitude;
                    rudeAccMovementTS = System.currentTimeMillis();
                    myToast.setText("Scanning for Fall Events");
                    myToast.show();
                    System.out.println("Current Acc is too high. Rude movement detected!");

                    fallDetectedTS = Long.valueOf(0);

                }

            }

        }


    }

    private double calculateFallDuration(double fallenDistance) {
        double durationInSec = Math.sqrt(fallenDistance / 0.5 / 9.80665);
        return durationInSec;
    }

    /** Compare Detected Fall Event Timestamps with new assumed Fall event to prevent multiple detections with the same fall event **/
    public static boolean isValidFallEvent(Double ts) {

        for (int i = 0; i < detectedFallEventsTimestamps.size(); i++) {

            if (Math.abs((detectedFallEventsTimestamps.get(i) - ts) / 1000) < timeDifferenceBetweenFallEvents) {
                return false;
            }

        }

        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        System.out.println("onPause");


    }

    @Override
    protected void onResume() {
        super.onResume();

        System.out.println("onResume");

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // NOT IN USE
    }

    public void onButtonTap(View v) {




        if(btnStartStop == false) {
            btnStartStop = true;
            myToast.setText("Started Sensing!");
            myToast.show();
            wakeLock.acquire();
            //onGoingCounter = 0;
            //onGoingAvrgAltitude = 0;

            //Create our Sensor Manager

            SM = (SensorManager)getSystemService(SENSOR_SERVICE);

            //ACCELEROMETER SENSOR

            mySensor = SM.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);



            //Register Sensor Listener
            SM.registerListener(this, mySensor, SensorManager.SENSOR_DELAY_FASTEST);
            SM.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_FASTEST);
            altiCounter = 0;
            altiAvrgAltitude = 0;
            altiPrevAvrgAltitude = 0;
            altiSum = 0;
            altiInitCounter = 0;
        } else {
            btnStartStop = false;
            myToast.setText("Stopped Sensing!");
            myToast.show();


            //TODO: Put this thread and the batched DB inserts in a new Method
            myAltitudeDB = new DatabaseHelper(this, dbName);
            myKalmanAltitudeDB = new DatabaseHelper(this, dbName);
            myAccDB = new DatabaseHelper(this, dbName);

            Runnable run = new Runnable() {
                @Override
                public void run() {

                    System.out.println("Started acc DB insert!");
                    myAccDB.getWritableDatabase().beginTransaction();
                    for (int i = 0; i < accelerationTimeStamps.size(); i ++) {
                        ContentValues contentValues = new ContentValues();
                        long result = 0;
                        contentValues.put("TimestampMilli", accelerationTimeStamps.get(i));
                        contentValues.put("TimestampNano", accelerationTimeStampsNano.get(i));
                        contentValues.put("Acceleration_Means", accelerationMeans.get(i));
                        result = myAccDB.getWritableDatabase().insert("Acceleration", null, contentValues);
                    }
                    myAccDB.getWritableDatabase().setTransactionSuccessful();
                    myAccDB.getWritableDatabase().endTransaction();
                    System.out.println("Finished acc DB insert!");


                    System.out.println("Started altitude DB insert!");
                    myAltitudeDB.getWritableDatabase().beginTransaction();
                    for(int i = 0; i < listOfAltitudes.size(); i++) {
                        ContentValues contentValues = new ContentValues();
                        long result = 0;
                        contentValues.put("TimestampMilli", altitudeTimeStamps.get(i));
                        contentValues.put("TimestampNano", altitudeTimeStampsNano.get(i));
                        contentValues.put("Altitude_inMeter", listOfAltitudes.get(i));
                        result = myAltitudeDB.getWritableDatabase().insert("Altitude", null, contentValues);
                    }
                    myAltitudeDB.getWritableDatabase().setTransactionSuccessful();
                    myAltitudeDB.getWritableDatabase().endTransaction();
                    System.out.println("Finished altitude DB insert!");


                    System.out.println("Started kalman altitude DB insert!");
                    myKalmanAltitudeDB.getWritableDatabase().beginTransaction();
                    for(int i = 0; i < listOfKalmanAltitudes.size(); i++) {
                        ContentValues contentValues = new ContentValues();
                        long result = 0;
                        contentValues.put("TimestampMilli", altitudeTimeStamps.get(i));
                        contentValues.put("TimestampNano", altitudeTimeStampsNano.get(i));
                        contentValues.put("Altitude_inMeter", listOfKalmanAltitudes.get(i));
                        result = myKalmanAltitudeDB.getWritableDatabase().insert("KalmanAltitude", null, contentValues);
                    }
                    myKalmanAltitudeDB.getWritableDatabase().setTransactionSuccessful();
                    myKalmanAltitudeDB.getWritableDatabase().endTransaction();
                    System.out.println("Finished kalman altitude DB insert!");



                    mHandler.sendEmptyMessage(0);

                }
            };
            Thread sensorDBinsertThread = new Thread(run);
            sensorDBinsertThread.start();

            wakeLock.release();
            SM.unregisterListener(this);


        }



    }

    public void settingsBtnTap(View v) {
        SM.unregisterListener(this);
        System.out.println("DATABASENAME: " + myDB.DATABASE_NAME);
        System.out.println(myDB.getTableAsString());
        Intent  settings = new Intent (MainActivity.this, SettingsActivity.class);
        startActivity(settings);
    }


    public void PlotBtnTap(View v) {
        SM.unregisterListener(this);
        System.out.println("DATABASENAME: " + myDB.DATABASE_NAME);
        Runnable run = new Runnable() {
            @Override
            public void run() {
                int last = myDB.getFallDistanceFromDB().length - 1;

                try {
                    System.out.println(myDB.getFallDistanceFromDB().length);
                    System.out.println(myDB.getFallDistanceFromDB()[0]);
                    System.out.println(myDB.getFallDistanceFromDB()[last]);

                } catch (Exception e) {
                    e.printStackTrace();
                }


                Intent plot = new Intent (MainActivity.this, GraphActivity.class);
                plot.putExtra("key", myDB.getFallDistanceFromDB());
                startActivity(plot);

            }
        };
        Thread sensorDBplotThread = new Thread(run);
        sensorDBplotThread.start();




    }
}
