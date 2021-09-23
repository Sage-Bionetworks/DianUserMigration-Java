/*
 * BSD 3-Clause License
 *
 * Copyright 2021  Sage Bionetworks. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1.  Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2.  Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3.  Neither the name of the copyright holder(s) nor the names of any contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission. No license is granted to the trademarks of
 * the copyright holders even if such marks are included in this software.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sagebionetoworks.dian.datamigration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BridgeUtil {

    // Do not change the name of these two variables, as it is used in CI to replace the values
    // However, to get the app to run, you must provide your own email/password,
    // NEVER commit your credentials to github
    public static String bridgeAdminEmail = "";
    public static String bridgeAdminPassword = "";

    // TODO: mdephillips 9/19/21 these will need switched to the official study created on bridge
    private static String STUDY_ID = "dian-validation-study";
    private static String BRIDGE_ID = "dian-validation";
    // TODO: mdephillips 9/19/21 this will need switched to an empty array list for production
    private static List<String> userDataGroups = Collections.singletonList("test_user");

    public static String MIGRATED_REPORT_ID = "Migrated";
    public static String AVAILABILITY_REPORT_ID = "Availability";
    public static String TEST_SCHEDULE_REPORT_ID = "TestSchedule";
    public static String COMPLETED_TESTS_REPORT_ID = "CompletedTests";

    private static String base = "https://webservices.sagebridge.org";
    private static String signIn = "/v3/auth/signIn";
    private static String participants = "/v3/participants";

    private static ZonedDateTime singletonReportDate =
            ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    private static String bridgeUrl(String suffix) {
        return base + suffix;
    }

    private static String userUrl(String userId) {
        return base + "/v3/participants/" + userId;
    }

    private static String getReportUrl(String userId, String reportId) {
        return base + "/v3/participants/" + userId + "/reports/" + reportId;
    }

    private static String postReportUrl(String userId, String reportId) {
        return base + "/v4/participants/" + userId + "/reports/" + reportId;
    }

    private static String getAllUserUrl(int offsetBy, int pageSize) {
        return bridgeUrl("/v3/participants") + "?offsetBy=" +
                offsetBy + "&pageSize=" + pageSize;
    }

    /**
     * @return authentication token
     * @throws MalformedURLException if sign in URL is bad
     * @throws IOException if there is an HTTP error
     */
    public static String authenticate() throws MalformedURLException, IOException {
        EmailSignIn emailSignIn = new EmailSignIn();
        emailSignIn.email = bridgeAdminEmail;
        emailSignIn.password = bridgeAdminPassword;
        emailSignIn.appId = BRIDGE_ID;

        ObjectWriter ow = new ObjectMapper().writer();
        String json = ow.writeValueAsString(emailSignIn);

        String response = bridgePOST(bridgeUrl(signIn), null, json);

        ObjectMapper mapper = new ObjectMapper();
        SignInResponse responseObj = mapper.readValue(response, SignInResponse.class);

        return responseObj.sessionToken;
    }

    public static <T> T getSingletonReport(
            String sessionToken, String reportId, String userId, Class<T> valueType)
            throws MalformedURLException, IOException {

        // Create a neat value object to hold the URL
        String urlStr = getReportUrl(userId, reportId);
        urlStr += "?startDate=1969-12-30&endDate=1970-01-03";

        String response = bridgeGET(urlStr, sessionToken);

        ObjectMapper mapper = new ObjectMapper();
        ReportDataList responseObj = mapper.readValue(response, ReportDataList.class);

        if (responseObj == null ||
            responseObj.items == null ||
            responseObj.items.isEmpty()) {
            return null;
        }

        String reportStr = responseObj.items.get(0).data;
        return mapper.readValue(reportStr, valueType);
    }

    public static void writeSingletonReport(
            String sessionToken, String userId, String reportId, String data)
            throws MalformedURLException, IOException {

        String urlStr = postReportUrl(userId, reportId);
        WriteReportData reportData = new WriteReportData();
        reportData.data = data;
        reportData.localDate = "1970-01-01";

        ObjectWriter ow = new ObjectMapper().writer();
        String json = ow.writeValueAsString(reportData);

        bridgePOST(urlStr, sessionToken, json);
    }

    public static UserList getAllUsers(String sessionToken) throws MalformedURLException, IOException {

        List<BridgeUserData> userList = new ArrayList<>();

        // Create a neat value object to hold the URL
        int offsetBy = 0;
        int pageSize = 100;
        boolean done = false;
        ObjectMapper mapper = new ObjectMapper();

        while (!done) {
            // Max page size is 100, so we need to page through
            String urlStr = getAllUserUrl(offsetBy, pageSize);
            String response = bridgeGET(urlStr, sessionToken);
            UserList list = mapper.readValue(response, UserList.class);
            int listSize = 0;
            if (list != null && list.items != null) {
                userList.addAll(list.items);
                listSize = list.items.size();
            }

            // Stop when we don't get a full page
            done = listSize < pageSize;
            offsetBy += pageSize;
        }

        UserList retVal = new UserList();
        retVal.items = userList;

        // Post-process the data, and extract ARC ID from the attributes for phone numbers
        for (BridgeUserData user: userList) {
            if ((user.arcId == null || user.arcId.isEmpty()) && user.externalId != null) {
                user.arcId = user.externalId;
            }
            if ((user.arcId == null || user.arcId.isEmpty()) && user.attributes != null) {
                user.arcId = user.attributes.ARC_ID;
            }
        }

        System.out.println("Successful GET of all " + userList.size() + " users ");

        return retVal;
    }

    /**
     * @param hmUsers unique users from Happy Medium's database
     * @param bridgeUserList existing users already created on bridge
     * @return the new, sored, list of Happy Medium user to Bridge user migration pair
     */
    public static List<MigrationPair> getUsersToMatch(
            List<SynapseUtil.HmUserData> hmUsers,
            List<BridgeUserData> bridgeUserList) {

        List<MigrationPair> migrationPairList = new ArrayList<>();

        // Loop through and create users if they don't already exist
        for(SynapseUtil.HmUserData user: hmUsers) {
            BridgeUserData bridgeUser = getBridgeUser(user.arcId, bridgeUserList);
            if (bridgeUser != null) {
                MigrationPair migration = new MigrationPair();
                migration.hmUser = user;
                migration.bridgeUser = bridgeUser;
                migrationPairList.add(migration);
            }
        }

        migrationPairList.sort((u1, u2) ->
                u1.bridgeUser.arcId.compareTo(u2.bridgeUser.arcId));

        return migrationPairList;
    }

    /**
     * @param arcId to find in the bridge users
     * @return null if no bridge user has that arc id, the bridge user otherwise
     */
    private static BridgeUserData getBridgeUser(String arcId, List<BridgeUserData> bridgeUserList) {
        for (BridgeUserData user: bridgeUserList) {
            if (user.arcId != null && user.arcId.equals(arcId)) {
                return user;
            }
        }
        return null;
    }

    /**
     * @param sessionToken to write study reports
     * @param migrationPairList all the happy medium and paired bridge users
     * @throws IOException if we fail to write a report to bridge
     */
    public static void writeAllUserReports(
            String sessionToken, List<BridgeUtil.MigrationPair> migrationPairList) throws IOException {

        ObjectWriter ow = new ObjectMapper().writer();

        // Now we have all the users created to upload their reports
        for (MigrationPair migration: migrationPairList) {
            BridgeUserData bridgeUser = migration.bridgeUser;
            SynapseUtil.HmUserData hmUser = migration.hmUser;

            if (shouldMigrate(sessionToken, bridgeUser)) {
                List<String> reportIds = new ArrayList<>();
                // Only re-write the user's data if they have not already migrated
                // Once a user successfully signs in through the sage app, this
                // user report will be uploaded to bridge, and we can see a user is migrated
                if (hmUser.completedTests != null) {
                    String json = ow.writeValueAsString(hmUser.completedTests);
                    writeSingletonReport(sessionToken, bridgeUser.id,
                            COMPLETED_TESTS_REPORT_ID, json);
                    reportIds.add(COMPLETED_TESTS_REPORT_ID);
                }
                if (hmUser.testSessionSchedule != null) {
                    String json = readFile(hmUser.testSessionSchedule);
                    writeSingletonReport(sessionToken, bridgeUser.id,
                            TEST_SCHEDULE_REPORT_ID, json);
                    reportIds.add(TEST_SCHEDULE_REPORT_ID);
                }
                if (hmUser.wakeSleepSchedule != null) {
                    String json = readFile(hmUser.wakeSleepSchedule);
                    writeSingletonReport(sessionToken, bridgeUser.id,
                            AVAILABILITY_REPORT_ID, json);
                    reportIds.add(AVAILABILITY_REPORT_ID);
                }
                System.out.println("Migrated user " + hmUser.arcId +
                        " reports " + String.join(", ", reportIds));
            } else {
                System.out.println("User " + hmUser.arcId + " has already migrated to Bridge.");
            }
        }
    }

    public static boolean shouldMigrate(
            String sessionToken, BridgeUserData bridgeUser) throws IOException {

        MigratedStatus isMigrated = getSingletonReport(sessionToken,
                MIGRATED_REPORT_ID, bridgeUser.id, MigratedStatus.class);

        return isMigrated == null || !isMigrated.status;
    }

    /**
     * @param file containing text
     * @return the string contents of the file
     * @throws IOException if somethings goes wrong
     */
    private static String readFile(File file) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static String raterId(String arcId) {
        // TODO: mdephillips 9/19/21 make this a look up table
        if(arcId.equals("000000")) {
            return null;
        }
        return "819594";
    }

    private static String phoneNum(String arcId) {
        // TODO: mdephillips 9/19/21 make this a look up table
        if(arcId.equals("000000")) {
            return "+14127208453";
        }
        return null;
    }

    private static String regionCode(String phoneNum) {
        // TODO: mdephillips 9/19/21 make this a look up table
        return "US";
    }

    /**
     * Delete all users that have data group "test_user"
     * @param sessionToken used to delete test users
     * @param migrationPairList to delete the bridge users
     * @throws IOException if we fail to delete a user
     */
    public static void deleteUserList(
            String sessionToken, List<BridgeUtil.MigrationPair> migrationPairList)
            throws IOException {

        for (MigrationPair migration: migrationPairList) {
            BridgeUtil.bridgeDELETE(userUrl(migration.bridgeUser.id), sessionToken);
            System.out.println("Successfully deleted " + migration.bridgeUser.arcId);
        }
    }

    /**
     * @param sessionToken used to create the user
     * @param arcID of the user, which will sign up with phone num or external ID
     * @return the UserData as reflected by a successful user sign up
     * @throws IOException if we fail to create a user on bridge
     */
    public static BridgeUserData createUser(String sessionToken, String arcID) throws IOException {
        String raterId = raterId(arcID);
        if (raterId == null) {
            String phoneNum = phoneNum(arcID);
            String regionCode = regionCode(phoneNum);
            return createPhoneNumberUser(sessionToken, arcID, phoneNum, regionCode);
        }

        SignupArcAndRaterIdUserData user = new SignupArcAndRaterIdUserData();
        UserArcIdAttributes attributes = new UserArcIdAttributes();
        attributes.ARC_ID = arcID;
        user.attributes = attributes;
        user.externalId = arcID;
        user.externalIds = new HashMap<>();
        user.externalIds.put(STUDY_ID, arcID);
        user.password = raterId;

        String json = new ObjectMapper().writer().writeValueAsString(user);
        String response = bridgePOST(bridgeUrl(participants), sessionToken, json);
        ObjectMapper mapper = new ObjectMapper();
        SignUpUserResponse bridgeResponse = mapper.readValue(response, SignUpUserResponse.class);

        if (bridgeResponse == null || bridgeResponse.identifier == null) {
            throw new IOException("Failed to create bridge user and get userID for ARC ID " + arcID);
        }

        BridgeUserData bridgeUser = new BridgeUserData();
        bridgeUser.id = bridgeResponse.identifier;
        bridgeUser.arcId = arcID;
        bridgeUser.attributes = attributes;
        bridgeUser.externalId = arcID;

        System.out.println("Successfully created " + arcID);

        return bridgeUser;
    }

    /**
     * @param sessionToken to create the user with phone number
     * @param arcId of the user
     * @param phoneNum of the user
     * @param regionCode of the user
     * @return the UserData as reflected by a successful user sign up
     * @throws IOException if we fail to create a user on bridge
     */
    private static BridgeUserData createPhoneNumberUser(
            String sessionToken, String arcId, String phoneNum, String regionCode)
            throws IOException {

        SignupPhoneUserData user = new SignupPhoneUserData();
        UserPhoneData phone = new UserPhoneData();
        phone.regionCode = regionCode;
        phone.number = phoneNum;
        user.phone = phone;
        UserArcIdAttributes attributes = new UserArcIdAttributes();
        attributes.ARC_ID = arcId;
        user.attributes = attributes;

        String json = new ObjectMapper().writer().writeValueAsString(user);
        String response = bridgePOST(bridgeUrl(participants), sessionToken, json);
        ObjectMapper mapper = new ObjectMapper();
        SignUpUserResponse bridgeResponse = mapper.readValue(response, SignUpUserResponse.class);

        if (bridgeResponse == null || bridgeResponse.identifier == null) {
            throw new IOException("Failed to create bridge user and get userID for ARC ID " + arcId);
        }

        BridgeUserData bridgeUser = new BridgeUserData();
        bridgeUser.id = bridgeResponse.identifier;
        bridgeUser.arcId = arcId;
        bridgeUser.attributes = attributes;
        bridgeUser.phone = phone;

        System.out.println("Successfully created " + arcId);

        return bridgeUser;
    }

    private static String bridgeDELETE(String url, String sessionToken) throws MalformedURLException, IOException {
        return bridgeApi(url, sessionToken, "DELETE", null);
    }

    private static String bridgeGET(String url, String sessionToken) throws MalformedURLException, IOException {
        return bridgeApi(url, sessionToken, "GET", null);
    }

    private static String bridgePOST(String url, String sessionToken) throws MalformedURLException, IOException {
        return bridgeApi(url, sessionToken, "POST", null);
    }

    private static String bridgePOST(String url, String sessionToken, String body) throws MalformedURLException, IOException {
        return bridgeApi(url, sessionToken, "POST", body);
    }

    private static String bridgeApi(String url, String sessionToken, String method, String body) throws MalformedURLException, IOException {
        // Open a connection(?) on the URL(??) and cast the response(???)
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);

        // Now it's "open", we can set the request method, headers etc.
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
        if (sessionToken != null) {
            connection.setRequestProperty("Bridge-Session", sessionToken);
        }
        connection.setDoOutput(true);

        if (body != null) {
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = body.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
        }

        // Manually converting the response body InputStream to APOD using Jackson
        BufferedReader br = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));

        StringBuilder response = new StringBuilder();
        String responseLine = null;
        while ((responseLine = br.readLine()) != null) {
            response.append(responseLine.trim());
        }

        return response.toString();
    }

    public static class EmailSignIn {
        public String appId;
        public String email;
        public String password;
    }

    public static class MigrationPair {
        public SynapseUtil.HmUserData hmUser;
        public BridgeUserData bridgeUser;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MigratedStatus {
        public boolean status;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserList {
        public List<BridgeUserData> items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignupArcAndRaterIdUserData {
        public String externalId;
        public Map<String, String> externalIds;
        public String password;
        public UserArcIdAttributes attributes;
        public String sharingScope = "all_qualified_researchers";
        public List<String> dataGroups = userDataGroups;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignUpUserResponse {
        public String identifier;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignupPhoneUserData {
        public UserPhoneData phone;
        public UserArcIdAttributes attributes;
        public String sharingScope = "all_qualified_researchers";
        public List<String> dataGroups = userDataGroups;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BridgeUserData {
        public UserPhoneData phone;
        public UserArcIdAttributes attributes;
        public String arcId;
        public String externalId;
        public String id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportDataList {
        public List<ReportData> items;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserPhoneData {
        public String number;
        public String regionCode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserArcIdAttributes {
        public String ARC_ID;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportData {
        public String data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WriteReportData {
        public String data;
        public String localDate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SignInResponse {
        public String sessionToken;
    }
}