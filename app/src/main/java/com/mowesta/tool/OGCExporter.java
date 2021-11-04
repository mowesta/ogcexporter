package com.mowesta.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import com.mowesta.feign.ApiClient;
import com.mowesta.feign.api.DefaultApi;
import com.mowesta.feign.auth.HttpBasicAuth;
import com.mowesta.feign.model.Device;
import com.mowesta.feign.model.DevicePage;
import com.mowesta.feign.model.Measurement;
import com.mowesta.feign.model.MeasurementPage;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import feign.Feign;

/**
 * A simple tool to export device data from MoWeSta into an OGC-conformant 
 * Sensor Observation Service.
 * 
 * @author Marcus
 */
public class OGCExporter {

    /**
     * The date format used to post observations.
     */
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");

    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    /**
     * The page size to download devices and measurements.
     */
    private static final int PAGE_SIZE = 100;


    /**
     * Runs the program. Depending on the parameter, the program either shows a list
     * of devices accessible to the user (if -d option is omitted) or imports the data
     * of a particular device into a Sensor Observation Service available at the 
     * target url (defined via the -t parameter).equals
     * 
     * @param args The command line parameters.
     */
    public static void main(String[] args) {
        Option target = new Option("t", "target", true, "Target url of the Sensor Observation Service.");
        Option username = new Option("u", "username", true, "User to connect to MoWeSta.");
        Option password = new Option("p", "password", true, "Password to connect to MoWeSta.");
        Option device = new Option("d", "device", true, "Device id of the device to export.");
        Options options = new Options().addOption(target).addOption(username).addOption(password).addOption(device);
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse(options, args);
            if ((line.hasOption(device) && !line.hasOption(target)) || !line.hasOption(username)) {
                printHelp(options, null);
                return;
            } 
            try {
                Long deviceId = null;
                if (line.hasOption(device)) {
                    deviceId = Long.parseLong(line.getOptionValue(device));
                }
                String pwd;
                if (! line.hasOption(password)) {
                    System.out.println("Enter password: ");
                    char[] chars = null;
                    if (System.console() != null) {
                       chars = System.console().readPassword();
                    }
                    if (chars == null) {
                        printHelp(options, "Failure. Reason: No password provided.");
                        return;        
                    } else {
                        pwd = new String(chars);
                    }
                } else {
                    pwd = line.getOptionValue(password);
                }
                if (deviceId != null) {
                    export(line.getOptionValue(target), line.getOptionValue(username), pwd, deviceId);
                } else {
                    list(line.getOptionValue(username), pwd);
                }
            } catch (NumberFormatException e) {
                printHelp(options, "Failure. Reason: Device id is invalid.");
            }
        }
        catch (ParseException exp) {
            printHelp(options, "Failure. Reason: " + exp.getMessage());
        }
    }

    /**
     * Prints the documentation of the command line parameters 
     * and optionally an error message.
     * 
     * @param options The options to prepare the help.
     * @param failure The failure to print or null, if none.
     */
    private static void printHelp(Options options, String failure) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(OGCExporter.class.getName(), options);
        if (failure != null) {
            System.out.println();
            System.out.println(failure);
        }
    }

    /**
     * Exports the device to the target service.
     * 
     * @param target The target.
     * @param username The username.
     * @param password The password.
     * @param device The device id.
     */
    private static void export(String target, String username, String password, long device) {
        DefaultApi api = getDefaultApi(username, password);
        OGCSosApi sos = getSosApi(target);
        // fetch the device
        Device d = api.getDevice(device);
        // create the sensor
        post(sos, replace(getTemplate("/sensor.xml"), getVariables(d)));
        long time = System.currentTimeMillis();
        int offset = 0;
        MeasurementPage p = null;
        do {
            // fetch the measurements
            p = api.getDeviceMeasurements(d.getId(), 0l, time, offset, PAGE_SIZE);
            // create the observations
            for (Measurement m: p.getElements()) {
                post(sos, replace(getTemplate("/observation.xml"), getVariables(d, m)));
            }    
            offset += PAGE_SIZE;
        } while (offset < p.getTotal());


    }

    /**
     * Posts a request to the Sensor Observation Service.
     * and prints the transmitted data as well as the response
     * or error result.
     * 
     * @param sos The sensor observation service.
     * @param body The xml body of the request.
     * @return The result or null, if an exception occured.
     */
    private static String post(OGCSosApi sos, String body) {
        System.out.println("---- Posting ---");
        System.out.println(body);
        try {
            String result = sos.post(body);
            System.out.println("---- Response ---");
            System.out.println(result); 
            return result;   
        } catch (Exception e) {
            System.out.println("---- Exception ---");
            e.printStackTrace();
            return null;
        }
    }



    /**
     * Lists the devices accessible to the user.
     * 
     * @param username The username.
     * @param password The password.
     */
    private static void list(String username, String password) {
        System.out.println("Devices in MoWeSta: ");
        int offset = 0;
        DevicePage page = null;
        do {
            page = getDefaultApi(username, password).getCurrentUserDevices(offset, PAGE_SIZE);
            for (Device d: page.getElements()) {
                System.out.printf("%d - %s-Device %s %s %s\n", d.getId(), d.getOs().name(), d.getManufacturer(), d.getModel(), d.getDevice());
            }
            offset += PAGE_SIZE;
        } while (offset < page.getTotal());
    }

    /**
     * Returns an api client to access MoWeSta with a given
     * set of credentials.
     * 
     * @param username The name of the user.
     * @param password The password of the user.
     * @return The api client.
     */
    private static DefaultApi getDefaultApi(String username, String password) {
        ApiClient c = new ApiClient();
        c.addAuthorization("basic", new HttpBasicAuth());
        c.setCredentials(username, password);
        return c.buildClient(DefaultApi.class);
    }

    /**
     * Returns an api client to post data to an sos.
     * 
     * @param target The url of the service endpoint to which we post.
     * @return the api client.
     */
    public static OGCSosApi getSosApi(String target) {
        ApiClient c = new ApiClient();
        c.setBasePath(target);
        c.setFeignBuilder(Feign.builder());
        return c.buildClient(OGCSosApi.class);
    }

    /**
     * Prepares a variable assignment to create a sensor.
     * 
     * @param device The device to add.
     */
    public static Map<String, String> getVariables(Device device) {
        Map<String, String> result = new HashMap<String, String>();
        result.put("offeringLabel", "MoWeSta Device " + device.getId());
        result.put("offering", "http://www.mowesta.com/offering/" + device.getId());
        result.put("sensorId", "mowesta-device-" + device.getId());
        result.put("sensorIdentifier", "http://www.mowesta.com/device/" + device.getId());
        result.put("longName",  "MoWeSta " + device.getId() + " (" + device.getModel() + " by " + device.getManufacturer() + " on " + device.getOs().name() + ")");
        result.put("shortName", "MoWeSta " + device.getId());
        result.put("feature", "http://www.mowesta.com/feature/" + device.getId());
        result.put("featureLabel", "MoWeSta Device " + device.getId() + " Location");
        return result;
    }

    /**
     * Prepares the variables to post an observation.
     * 
     * @param device The device that made a measurement.
     * @param measurement The measurement of the device.
     */
    public static Map<String, String> getVariables(Device device, Measurement measurement) {
        Map<String, String> result = new HashMap<String, String>();
        result.put("offering", "http://www.mowesta.com/offering/" + device.getId());
        result.put("sensorIdentifier", "http://www.mowesta.com/device/" + device.getId());
        result.put("observationId", "mowesta-measurement-" + measurement.getId());
        result.put("observationIdentifier", "http://www.mowesta.com/measurement/" + measurement.getId());
        result.put("time", DATE_FORMAT.format(new Date(measurement.getTime())) + "+00:00");
        result.put("feature", "http://www.mowesta.com/feature/" + device.getId());
        result.put("featureId", "mowesta-position-" + measurement.getId());
        result.put("featureIdentifier", "http://www.mowesta.com/position/" + measurement.getId());
        result.put("humidity", Double.toString(measurement.getHumidity()!=null ?  measurement.getHumidity() : 0));
        result.put("pressure", Double.toString(measurement.getPressure()!=null ?  measurement.getPressure() : 0));
        result.put("temperature", Double.toString(measurement.getTemperature()!=null ?  measurement.getTemperature() : 0));
        result.put("position", Double.toString(measurement.getCoordinate().getLatitude()) + " " + Double.toString(measurement.getCoordinate().getLongitude()));
        return result;        
    }


    /**
     * Replaces the variables in the template with the variable assignment.
     * 
     * @param template The template string in which we replace something.
     * @param variables The variables with variable name and assignment.
     */
    public static String replace(String template, Map<String, String> variables) {
        for (Map.Entry<String, String> entry: variables.entrySet()) {
            template = template.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }

    /**
     * Loads a request template from the resources.
     * 
     * @param name The name of the template.
     */
    public static String getTemplate(String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(OGCExporter.class.getResourceAsStream(name), "UTF-8"))) {
            StringBuilder b = new StringBuilder();
            String next = reader.readLine();
            while (next != null) {
                b.append(next);
                b.append("\n");
                next = reader.readLine();
            }
            return b.toString();
         } catch (IOException e) {
             throw new UncheckedIOException(e);
         }
    }


}
