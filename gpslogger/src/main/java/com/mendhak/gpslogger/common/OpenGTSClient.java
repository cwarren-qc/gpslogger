/*
*    This file is part of GPSLogger for Android.
*
*    GPSLogger for Android is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 2 of the License, or
*    (at your option) any later version.
*
*    GPSLogger for Android is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.mendhak.gpslogger.common;

import android.content.Context;
import android.location.Location;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * OpenGTS Client
 *
 * @author Francisco Reynoso <franole @ gmail.com>
 */
public class OpenGTSClient implements IActionListener {

    private static final org.slf4j.Logger tracer = LoggerFactory.getLogger(OpenGTSClient.class.getSimpleName());


    private final static ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(128), new RejectionHandler());

    private IActionListener callback;
    private String server;
    private Integer port;
    private String path;

    public OpenGTSClient(String server, Integer port, String path, IActionListener callback) {
        this.server = server;
        this.port = port;
        this.path = path;
        this.callback = callback;
    }

    public void sendHTTP(String id, String accountName, Location location) {
        sendHTTP(id, accountName, new Location[]{location});
    }

    public void sendHTTP(String id, String accountName, Location[] locations) {

        OpenGtsUrlLogHandler handler = new OpenGtsUrlLogHandler( server, port, path, id, accountName, locations, this);
        EXECUTOR.execute(handler);
    }

    public void sendRAW(String id, Location location) {
        // TODO
    }

    private void sendRAW(String id, Location[] locations) {
        // TODO
    }


    @Override
    public void OnComplete() {
        callback.OnComplete();
    }

    @Override
    public void OnFailure() {
        callback.OnFailure();
    }
}

class OpenGtsUrlLogHandler implements Runnable {

    private static final org.slf4j.Logger tracer = LoggerFactory.getLogger(OpenGtsUrlLogHandler.class.getSimpleName());

    String server;
    Integer port;
    String path;
    String id;
    String accountName;
    Location[] locations;
    private int locationsCount = 0;
    private int sentLocationsCount = 0;
    private IActionListener callback;

    OpenGtsUrlLogHandler(String server, Integer port, String path, String id, String accountName,
                         Location[] locations, IActionListener callback){
        this.id = id;
        this.accountName = accountName;
        this.locations = locations;
        this.server = server;
        this.port = port;
        this.path = path;
        this.callback = callback;
    }


    private String getURL() {
        StringBuilder url = new StringBuilder();
        url.append(server);
        if (port != null) {
            url.append(":");
            url.append(port);
        }
        if (path != null) {
            url.append(path);
        }
        return url.toString();
    }

    /**
     * Send locations sing HTTP GET request to the server
     * <p/>
     * See <a href="http://opengts.sourceforge.net/OpenGTS_Config.pdf">OpenGTS_Config.pdf</a>
     * section 9.1.2 Default "gprmc" Configuration
     *
     */
    @Override
    public void run() {
        try {
            HttpURLConnection conn = null;
            locationsCount = locations.length;
            StringBuilder url = new StringBuilder();
            url.append("http://");
            url.append(getURL());

            for (Location loc : locations) {

                url.append("?id=" + URLEncoder.encode(id, "UTF-8"));
                url.append("&dev=" + URLEncoder.encode(id, "UTF-8"));

                if(!Utilities.IsNullOrEmpty(accountName)){
                    url.append("&acct=" + URLEncoder.encode(accountName, "UTF-8"));
                } else {
                    url.append("&acct=" + URLEncoder.encode(id, "UTF-8"));
                }

                url.append("&code=0xF020");
                url.append("&gprmc=" + URLEncoder.encode(GPRMCEncode(loc), "UTF-8"));
                url.append("&alt=" + URLEncoder.encode( String.valueOf(loc.getAltitude()), "UTF-8"));

                tracer.debug("Sending to URL: " + url.toString());
                URL gtsUrl = new URL(url.toString());

                conn = (HttpURLConnection) gtsUrl.openConnection();
                conn.setRequestMethod("GET");

                Scanner s;
                if(conn.getResponseCode() != 200){
                    s = new Scanner(conn.getErrorStream());
                    tracer.error("Status code: " + String.valueOf(conn.getResponseCode()));
                    tracer.error(s.useDelimiter("\\A").next());
                    OnFailure();
                } else {
                    s = new Scanner(conn.getInputStream());
                    tracer.debug("Status code: " + String.valueOf(conn.getResponseCode()));
                    tracer.debug(s.useDelimiter("\\A").next());
                    OnCompleteLocation();
                }

            }
        } catch (Exception e) {
            tracer.error("OpenGTSClient.sendHTTP", e);
            OnFailure();
        }
    }

    /**
     * Encode a location as GPRMC string data.
     * <p/>
     * For details check org.opengts.util.Nmea0183#_parse_GPRMC(String)
     * (OpenGTS source)
     *
     * @param loc location
     * @return GPRMC data
     */
    public static String GPRMCEncode(Location loc) {
        DecimalFormatSymbols dfs = new DecimalFormatSymbols(Locale.US);
        DecimalFormat f = new DecimalFormat("0.000000", dfs);

        String gprmc = String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,,",
                "$GPRMC",
                NMEAGPRMCTime(new Date(loc.getTime())),
                "A",
                NMEAGPRMCCoord(Math.abs(loc.getLatitude())),
                (loc.getLatitude() >= 0) ? "N" : "S",
                NMEAGPRMCCoord(Math.abs(loc.getLongitude())),
                (loc.getLongitude() >= 0) ? "E" : "W",
                f.format(MetersPerSecondToKnots(loc.getSpeed())),
                f.format(loc.getBearing()),
                NMEAGPRMCDate(new Date(loc.getTime()))
        );

        gprmc += "*" + NMEACheckSum(gprmc);

        return gprmc;
    }

    public static String NMEAGPRMCTime(Date dateToFormat) {
        SimpleDateFormat sdf = new SimpleDateFormat("HHmmss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(dateToFormat);
    }

    public static String NMEAGPRMCDate(Date dateToFormat) {
        SimpleDateFormat sdf = new SimpleDateFormat("ddMMyy");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(dateToFormat);
    }

    public static String NMEAGPRMCCoord(double coord) {
        // “DDDMM.MMMMM”
        int degrees = (int) coord;
        double minutes = (coord - degrees) * 60;

        DecimalFormat df = new DecimalFormat("00.00000", new DecimalFormatSymbols(Locale.US));
        StringBuilder rCoord = new StringBuilder();
        rCoord.append(degrees);
        rCoord.append(df.format(minutes));

        return rCoord.toString();
    }


    public static String NMEACheckSum(String msg) {
        int chk = 0;
        for (int i = 1; i < msg.length(); i++) {
            chk ^= msg.charAt(i);
        }
        String chk_s = Integer.toHexString(chk).toUpperCase();
        while (chk_s.length() < 2) {
            chk_s = "0" + chk_s;
        }
        return chk_s;
    }

    /**
     * Converts given meters/second to nautical mile/hour.
     *
     * @param mps meters per second
     * @return knots
     */
    public static double MetersPerSecondToKnots(double mps) {
        // Google "meters per second to knots"
        return mps * 1.94384449;
    }

    public void OnCompleteLocation() {
        sentLocationsCount += 1;
        tracer.debug("Sent locations count: " + sentLocationsCount + "/" + locationsCount);
        if (locationsCount == sentLocationsCount) {
            OnComplete();
        }
    }

    public void OnComplete() {
        callback.OnComplete();
    }

    public void OnFailure() {
        //httpClient.cancelRequests(applicationContext, true);
        callback.OnFailure();
    }
}
