package com.parking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class ParkingServer {

    public static void main(String[] args) throws IOException {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080")
        );

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        // =========================
        // API ENDPOINTS
        // =========================

        server.createContext(
                "/api/test",
                ParkingServer::test
        );

        server.createContext(
                "/api/login",
                ParkingServer::login
        );

        server.createContext(
                "/api/vehicle/entry",
                ParkingServer::vehicleEntry
        );

        server.createContext(
                "/api/vehicle/search",
                ParkingServer::vehicleSearch
        );

        server.createContext(
                "/api/vehicle/exit",
                ParkingServer::vehicleExit
        );

        server.createContext(
                "/api/dashboard",
                ParkingServer::dashboard
        );

        server.createContext(
                "/api/parking/history",
                ParkingServer::parkingHistory
        );

        server.createContext(
                "/api/parking/slots",
                ParkingServer::parkingSlots
        );

        // =========================
        // FRONTEND / STATIC FILES
        // =========================

        server.createContext(
                "/",
                ParkingServer::staticFiles
        );

        server.start();

        System.out.println("=================================");
        System.out.println("Parking Management Server Started");
        System.out.println("Port: " + port);
        System.out.println("=================================");
    }


    // =====================================================
    // TEST API
    // =====================================================

    private static void test(HttpExchange exchange)
            throws IOException {

        sendResponse(
                exchange,
                200,
                """
                {
                    "success": true,
                    "message": "Parking Management System Backend is running!"
                }
                """
        );
    }


    // =====================================================
    // LOGIN API
    // =====================================================

    private static void login(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "POST method required"
                    }
                    """
            );

            return;
        }

        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        Map<String, String> formData =
                parseFormData(requestBody);

        String username =
                formData.get("username");

        String password =
                formData.get("password");

        if (username == null ||
                password == null) {

            sendResponse(
                    exchange,
                    400,
                    """
                    {
                        "success": false,
                        "message": "Username and password are required"
                    }
                    """
            );

            return;
        }

        String sql = """
                SELECT user_id
                FROM users
                WHERE username = ?
                AND password = ?
                """;

        try (
                Connection connection =
                        DatabaseConnection.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setString(1, username);
            statement.setString(2, password);

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (resultSet.next()) {

                    sendResponse(
                            exchange,
                            200,
                            """
                            {
                                "success": true,
                                "message": "Login successful"
                            }
                            """
                    );

                } else {

                    sendResponse(
                            exchange,
                            401,
                            """
                            {
                                "success": false,
                                "message": "Invalid username or password"
                            }
                            """
                    );
                }
            }

        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error"
                    }
                    """
            );
        }
    }


    // =====================================================
    // VEHICLE ENTRY API
    // =====================================================

    private static void vehicleEntry(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "POST method required"
                    }
                    """
            );

            return;
        }

        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        Map<String, String> data =
                parseFormData(requestBody);

        String vehicleNumber =
                data.get("vehicleNumber");

        String ownerName =
                data.get("ownerName");

        String phoneNumber =
                data.get("phoneNumber");

        String vehicleType =
                data.get("vehicleType");

        // =========================
        // VALIDATE INPUT
        // =========================

        if (vehicleNumber == null ||
                ownerName == null ||
                phoneNumber == null ||
                vehicleType == null ||
                vehicleNumber.isBlank() ||
                ownerName.isBlank() ||
                vehicleType.isBlank()) {

            sendResponse(
                    exchange,
                    400,
                    """
                    {
                        "success": false,
                        "message": "All required fields must be filled"
                    }
                    """
            );

            return;
        }

        Connection connection = null;

        try {

            connection =
                    DatabaseConnection.getConnection();

            connection.setAutoCommit(false);

            // =========================
            // 1. CHECK IF ALREADY PARKED
            // =========================

            String checkSql = """
                    SELECT pr.record_id
                    FROM parking_records pr
                    JOIN vehicles v
                        ON pr.vehicle_id = v.vehicle_id
                    WHERE v.vehicle_number = ?
                    AND pr.status = 'Parked'
                    """;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(checkSql)
            ) {

                statement.setString(
                        1,
                        vehicleNumber
                );

                try (
                        ResultSet resultSet =
                                statement.executeQuery()
                ) {

                    if (resultSet.next()) {

                        connection.rollback();

                        sendResponse(
                                exchange,
                                400,
                                """
                                {
                                    "success": false,
                                    "message": "This vehicle is already parked"
                                }
                                """
                        );

                        return;
                    }
                }
            }


            // =========================
            // 2. FIND AVAILABLE SLOT
            // =========================

            String slotSql = """
                    SELECT slot_id, slot_number
                    FROM parking_slots
                    WHERE status = 'Available'
                    ORDER BY slot_id
                    LIMIT 1
                    """;

            int slotId;
            String slotNumber;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(slotSql);

                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {

                    connection.rollback();

                    sendResponse(
                            exchange,
                            400,
                            """
                            {
                                "success": false,
                                "message": "No parking slots available"
                            }
                            """
                    );

                    return;
                }

                slotId =
                        resultSet.getInt("slot_id");

                slotNumber =
                        resultSet.getString("slot_number");
            }


            // =========================
            // 3. CHECK IF VEHICLE EXISTS
            // =========================

            int vehicleId;

            String existingVehicleSql = """
                    SELECT vehicle_id
                    FROM vehicles
                    WHERE vehicle_number = ?
                    """;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    existingVehicleSql
                            )
            ) {

                statement.setString(
                        1,
                        vehicleNumber
                );

                try (
                        ResultSet resultSet =
                                statement.executeQuery()
                ) {

                    if (resultSet.next()) {

                        vehicleId =
                                resultSet.getInt(
                                        "vehicle_id"
                                );

                        String updateVehicleSql = """
                                UPDATE vehicles
                                SET
                                    owner_name = ?,
                                    phone_number = ?,
                                    vehicle_type = ?
                                WHERE vehicle_id = ?
                                """;

                        try (
                                PreparedStatement updateStatement =
                                        connection.prepareStatement(
                                                updateVehicleSql
                                        )
                        ) {

                            updateStatement.setString(
                                    1,
                                    ownerName
                            );

                            updateStatement.setString(
                                    2,
                                    phoneNumber
                            );

                            updateStatement.setString(
                                    3,
                                    vehicleType
                            );

                            updateStatement.setInt(
                                    4,
                                    vehicleId
                            );

                            updateStatement.executeUpdate();
                        }

                    } else {

                        // =========================
                        // 4. INSERT NEW VEHICLE
                        // =========================

                        String vehicleSql = """
                                INSERT INTO vehicles
                                (
                                    vehicle_number,
                                    owner_name,
                                    phone_number,
                                    vehicle_type
                                )
                                VALUES (?, ?, ?, ?)
                                """;

                        try (
                                PreparedStatement insertStatement =
                                        connection.prepareStatement(
                                                vehicleSql,
                                                java.sql.Statement.RETURN_GENERATED_KEYS
                                        )
                        ) {

                            insertStatement.setString(
                                    1,
                                    vehicleNumber
                            );

                            insertStatement.setString(
                                    2,
                                    ownerName
                            );

                            insertStatement.setString(
                                    3,
                                    phoneNumber
                            );

                            insertStatement.setString(
                                    4,
                                    vehicleType
                            );

                            insertStatement.executeUpdate();

                            try (
                                    ResultSet keys =
                                            insertStatement.getGeneratedKeys()
                            ) {

                                if (!keys.next()) {

                                    throw new Exception(
                                            "Vehicle ID was not generated"
                                    );
                                }

                                vehicleId =
                                        keys.getInt(1);
                            }
                        }
                    }
                }
            }


            // =========================
            // 5. CREATE PARKING RECORD
            // =========================

            String recordSql = """
                    INSERT INTO parking_records
                    (
                        vehicle_id,
                        slot_id,
                        entry_time,
                        status
                    )
                    VALUES (?, ?, NOW(), 'Parked')
                    """;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(recordSql)
            ) {

                statement.setInt(
                        1,
                        vehicleId
                );

                statement.setInt(
                        2,
                        slotId
                );

                statement.executeUpdate();
            }


            // =========================
            // 6. MARK SLOT OCCUPIED
            // =========================

            String updateSlotSql = """
                    UPDATE parking_slots
                    SET status = 'Occupied'
                    WHERE slot_id = ?
                    """;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    updateSlotSql
                            )
            ) {

                statement.setInt(
                        1,
                        slotId
                );

                statement.executeUpdate();
            }


            // =========================
            // 7. COMMIT
            // =========================

            connection.commit();


            // =========================
            // 8. RESPONSE
            // =========================

            String response = """
                    {
                        "success": true,
                        "message": "Vehicle parked successfully",
                        "vehicleNumber": "%s",
                        "slotNumber": "%s"
                    }
                    """.formatted(
                    escapeJson(vehicleNumber),
                    escapeJson(slotNumber)
            );

            sendResponse(
                    exchange,
                    200,
                    response
            );

        } catch (Exception e) {

            e.printStackTrace();

            if (connection != null) {

                try {
                    connection.rollback();
                } catch (Exception ignored) {
                }
            }

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error while parking vehicle"
                    }
                    """
            );

        } finally {

            if (connection != null) {

                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }


    // =====================================================
    // VEHICLE SEARCH API
    // =====================================================

    private static void vehicleSearch(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "POST method required"
                    }
                    """
            );

            return;
        }

        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        Map<String, String> data =
                parseFormData(requestBody);

        String vehicleNumber =
                data.get("vehicleNumber");

        if (vehicleNumber == null ||
                vehicleNumber.isBlank()) {

            sendResponse(
                    exchange,
                    400,
                    """
                    {
                        "success": false,
                        "message": "Vehicle number is required"
                    }
                    """
            );

            return;
        }

        String sql = """
                SELECT
                    v.vehicle_number,
                    v.vehicle_type,
                    ps.slot_number,
                    pr.entry_time
                FROM parking_records pr
                JOIN vehicles v
                    ON pr.vehicle_id = v.vehicle_id
                JOIN parking_slots ps
                    ON pr.slot_id = ps.slot_id
                WHERE v.vehicle_number = ?
                AND pr.status = 'Parked'
                """;

        try (
                Connection connection =
                        DatabaseConnection.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setString(
                    1,
                    vehicleNumber
            );

            try (
                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (!resultSet.next()) {

                    sendResponse(
                            exchange,
                            404,
                            """
                            {
                                "success": false,
                                "message": "Vehicle is not currently parked"
                            }
                            """
                    );

                    return;
                }

                String number =
                        resultSet.getString(
                                "vehicle_number"
                        );

                String type =
                        resultSet.getString(
                                "vehicle_type"
                        );

                String slot =
                        resultSet.getString(
                                "slot_number"
                        );

                String entryTime =
                        resultSet
                                .getTimestamp("entry_time")
                                .toString();

                String response = """
                        {
                            "success": true,
                            "vehicleNumber": "%s",
                            "vehicleType": "%s",
                            "slotNumber": "%s",
                            "entryTime": "%s"
                        }
                        """.formatted(
                        escapeJson(number),
                        escapeJson(type),
                        escapeJson(slot),
                        escapeJson(entryTime)
                );

                sendResponse(
                        exchange,
                        200,
                        response
                );
            }

        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error"
                    }
                    """
            );
        }
    }


    // =====================================================
    // VEHICLE EXIT API
    // =====================================================

    private static void vehicleExit(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("POST")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "POST method required"
                    }
                    """
            );

            return;
        }

        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        Map<String, String> data =
                parseFormData(requestBody);

        String vehicleNumber =
                data.get("vehicleNumber");

        if (vehicleNumber == null ||
                vehicleNumber.isBlank()) {

            sendResponse(
                    exchange,
                    400,
                    """
                    {
                        "success": false,
                        "message": "Vehicle number is required"
                    }
                    """
            );

            return;
        }

        Connection connection = null;

        try {

            connection =
                    DatabaseConnection.getConnection();

            connection.setAutoCommit(false);


            // =================================================
            // 1. FIND ACTIVE PARKING RECORD
            // =================================================

            String findSql = """
                    SELECT
                        pr.record_id,
                        pr.slot_id,
                        pr.entry_time,
                        TIMESTAMPDIFF(
                            MINUTE,
                            pr.entry_time,
                            NOW()
                        ) AS duration_minutes,
                        v.vehicle_type
                    FROM parking_records pr
                    JOIN vehicles v
                        ON pr.vehicle_id = v.vehicle_id
                    WHERE v.vehicle_number = ?
                    AND pr.status = 'Parked'
                    """;

            int recordId;
            int slotId;
            String vehicleType;
            long durationMinutes;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(findSql)
            ) {

                statement.setString(
                        1,
                        vehicleNumber
                );

                try (
                        ResultSet resultSet =
                                statement.executeQuery()
                ) {

                    if (!resultSet.next()) {

                        connection.rollback();

                        sendResponse(
                                exchange,
                                404,
                                """
                                {
                                    "success": false,
                                    "message": "Vehicle is not currently parked"
                                }
                                """
                        );

                        return;
                    }

                    recordId =
                            resultSet.getInt(
                                    "record_id"
                            );

                    slotId =
                            resultSet.getInt(
                                    "slot_id"
                            );

                    vehicleType =
                            resultSet.getString(
                                    "vehicle_type"
                            );

                    durationMinutes =
                            resultSet.getLong(
                                    "duration_minutes"
                            );
                }
            }


            // =================================================
            // 2. CALCULATE BILLABLE HOURS
            // =================================================

            long durationHours =
                    Math.max(
                            1,
                            (long) Math.ceil(
                                    durationMinutes / 60.0
                            )
                    );


            // =================================================
            // 3. CALCULATE PARKING RATE
            // =================================================

            double hourlyRate;

            if (
                    vehicleType != null &&
                            vehicleType.equalsIgnoreCase("Bike")
            ) {

                hourlyRate = 10;

            } else if (
                    vehicleType != null &&
                            vehicleType.equalsIgnoreCase("SUV")
            ) {

                hourlyRate = 30;

            } else {

                hourlyRate = 20;
            }


            // =================================================
            // 4. CALCULATE TOTAL FEE
            // =================================================

            double parkingFee =
                    durationHours * hourlyRate;


            // =================================================
            // 5. UPDATE PARKING RECORD
            // =================================================

            String updateRecordSql = """
                    UPDATE parking_records
                    SET
                        exit_time = NOW(),
                        duration_hours = ?,
                        parking_fee = ?,
                        status = 'Exited'
                    WHERE record_id = ?
                    """;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    updateRecordSql
                            )
            ) {

                statement.setDouble(
                        1,
                        durationHours
                );

                statement.setDouble(
                        2,
                        parkingFee
                );

                statement.setInt(
                        3,
                        recordId
                );

                statement.executeUpdate();
            }


            // =================================================
            // 6. FREE PARKING SLOT
            // =================================================

            String updateSlotSql = """
                    UPDATE parking_slots
                    SET status = 'Available'
                    WHERE slot_id = ?
                    """;

            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    updateSlotSql
                            )
            ) {

                statement.setInt(
                        1,
                        slotId
                );

                statement.executeUpdate();
            }


            // =================================================
            // 7. COMMIT TRANSACTION
            // =================================================

            connection.commit();


            // =================================================
            // 8. SEND RESPONSE
            // =================================================

            String response = """
                    {
                        "success": true,
                        "message": "Vehicle exit completed",
                        "vehicleNumber": "%s",
                        "durationHours": %d,
                        "parkingFee": %.2f
                    }
                    """.formatted(
                    escapeJson(vehicleNumber),
                    durationHours,
                    parkingFee
            );

            sendResponse(
                    exchange,
                    200,
                    response
            );

        } catch (Exception e) {

            e.printStackTrace();

            if (connection != null) {

                try {
                    connection.rollback();
                } catch (Exception ignored) {
                }
            }

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error while processing vehicle exit"
                    }
                    """
            );

        } finally {

            if (connection != null) {

                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }


    // =====================================================
    // DASHBOARD API
    // =====================================================

    private static void dashboard(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("GET")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "GET method required"
                    }
                    """
            );

            return;
        }

        String totalSlotsSql = """
                SELECT COUNT(*) AS total_slots
                FROM parking_slots
                """;

        String availableSlotsSql = """
                SELECT COUNT(*) AS available_slots
                FROM parking_slots
                WHERE status = 'Available'
                """;

        String occupiedSlotsSql = """
                SELECT COUNT(*) AS occupied_slots
                FROM parking_slots
                WHERE status = 'Occupied'
                """;

        String parkedVehiclesSql = """
                SELECT COUNT(*) AS parked_vehicles
                FROM parking_records
                WHERE status = 'Parked'
                """;

        try (
                Connection connection =
                        DatabaseConnection.getConnection()
        ) {

            int totalSlots = 0;
            int availableSlots = 0;
            int occupiedSlots = 0;
            int parkedVehicles = 0;

            // TOTAL SLOTS
            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    totalSlotsSql
                            );

                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (resultSet.next()) {

                    totalSlots =
                            resultSet.getInt(
                                    "total_slots"
                            );
                }
            }

            // AVAILABLE SLOTS
            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    availableSlotsSql
                            );

                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (resultSet.next()) {

                    availableSlots =
                            resultSet.getInt(
                                    "available_slots"
                            );
                }
            }

            // OCCUPIED SLOTS
            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    occupiedSlotsSql
                            );

                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (resultSet.next()) {

                    occupiedSlots =
                            resultSet.getInt(
                                    "occupied_slots"
                            );
                }
            }

            // PARKED VEHICLES
            try (
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    parkedVehiclesSql
                            );

                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                if (resultSet.next()) {

                    parkedVehicles =
                            resultSet.getInt(
                                    "parked_vehicles"
                            );
                }
            }

            String response = """
                    {
                        "success": true,
                        "totalSlots": %d,
                        "availableSlots": %d,
                        "occupiedSlots": %d,
                        "parkedVehicles": %d
                    }
                    """.formatted(
                    totalSlots,
                    availableSlots,
                    occupiedSlots,
                    parkedVehicles
            );

            sendResponse(
                    exchange,
                    200,
                    response
            );

        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error while loading dashboard"
                    }
                    """
            );
        }
    }


    // =====================================================
    // PARKING HISTORY API
    // =====================================================

    private static void parkingHistory(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("GET")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "GET method required"
                    }
                    """
            );

            return;
        }

        String sql = """
                SELECT
                    v.vehicle_number,
                    v.vehicle_type,
                    ps.slot_number,
                    pr.entry_time,
                    pr.exit_time,
                    pr.duration_hours,
                    pr.parking_fee,
                    pr.status
                FROM parking_records pr
                JOIN vehicles v
                    ON pr.vehicle_id = v.vehicle_id
                JOIN parking_slots ps
                    ON pr.slot_id = ps.slot_id
                ORDER BY pr.entry_time DESC
                """;

        StringBuilder json =
                new StringBuilder(
                        "{\"success\":true,\"records\":["
                );

        try (
                Connection connection =
                        DatabaseConnection.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql);

                ResultSet resultSet =
                        statement.executeQuery()
        ) {

            boolean first = true;

            while (resultSet.next()) {

                if (!first) {
                    json.append(",");
                }

                first = false;

                String vehicleNumber =
                        resultSet.getString(
                                "vehicle_number"
                        );

                String vehicleType =
                        resultSet.getString(
                                "vehicle_type"
                        );

                String slotNumber =
                        resultSet.getString(
                                "slot_number"
                        );

                java.sql.Timestamp entryTimestamp =
                        resultSet.getTimestamp(
                                "entry_time"
                        );

                java.sql.Timestamp exitTimestamp =
                        resultSet.getTimestamp(
                                "exit_time"
                        );

                String entryTime =
                        entryTimestamp != null
                                ? entryTimestamp.toString()
                                : null;

                String exitTime =
                        exitTimestamp != null
                                ? exitTimestamp.toString()
                                : null;

                double duration =
                        resultSet.getDouble(
                                "duration_hours"
                        );

                double fee =
                        resultSet.getDouble(
                                "parking_fee"
                        );

                String status =
                        resultSet.getString(
                                "status"
                        );

                json.append("{");

                json.append("\"vehicleNumber\":\"")
                        .append(
                                escapeJson(vehicleNumber)
                        )
                        .append("\",");

                json.append("\"vehicleType\":\"")
                        .append(
                                escapeJson(vehicleType)
                        )
                        .append("\",");

                json.append("\"slotNumber\":\"")
                        .append(
                                escapeJson(slotNumber)
                        )
                        .append("\",");

                json.append("\"entryTime\":");

                if (entryTime == null) {

                    json.append("null");

                } else {

                    json.append("\"")
                            .append(
                                    escapeJson(entryTime)
                            )
                            .append("\"");
                }

                json.append(",");

                json.append("\"exitTime\":");

                if (exitTime == null) {

                    json.append("null");

                } else {

                    json.append("\"")
                            .append(
                                    escapeJson(exitTime)
                            )
                            .append("\"");
                }

                json.append(",");

                json.append("\"durationHours\":")
                        .append(duration)
                        .append(",");

                json.append("\"parkingFee\":")
                        .append(fee)
                        .append(",");

                json.append("\"status\":\"")
                        .append(
                                escapeJson(status)
                        )
                        .append("\"");

                json.append("}");
            }

            json.append("]}");

            sendResponse(
                    exchange,
                    200,
                    json.toString()
            );

        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error while loading parking history"
                    }
                    """
            );
        }
    }


    // =====================================================
    // PARKING SLOTS API
    // =====================================================

    private static void parkingSlots(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("GET")) {

            sendResponse(
                    exchange,
                    405,
                    """
                    {
                        "success": false,
                        "message": "GET method required"
                    }
                    """
            );

            return;
        }

        String sql = """
                SELECT
                    slot_id,
                    slot_number,
                    status
                FROM parking_slots
                ORDER BY slot_id
                """;

        StringBuilder json =
                new StringBuilder(
                        "{\"success\":true,\"slots\":["
                );

        try (
                Connection connection =
                        DatabaseConnection.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql);

                ResultSet resultSet =
                        statement.executeQuery()
        ) {

            boolean first = true;

            while (resultSet.next()) {

                if (!first) {
                    json.append(",");
                }

                first = false;

                int slotId =
                        resultSet.getInt("slot_id");

                String slotNumber =
                        resultSet.getString("slot_number");

                String status =
                        resultSet.getString("status");

                json.append("{");

                json.append("\"slotId\":")
                        .append(slotId)
                        .append(",");

                json.append("\"slotNumber\":\"")
                        .append(
                                escapeJson(slotNumber)
                        )
                        .append("\",");

                json.append("\"status\":\"")
                        .append(
                                escapeJson(status)
                        )
                        .append("\"");

                json.append("}");
            }

            json.append("]}");

            sendResponse(
                    exchange,
                    200,
                    json.toString()
            );

        } catch (Exception e) {

            e.printStackTrace();

            sendResponse(
                    exchange,
                    500,
                    """
                    {
                        "success": false,
                        "message": "Database error while loading parking slots"
                    }
                    """
            );
        }
    }


    // =====================================================
    // FRONTEND / STATIC FILE SERVER
    // =====================================================

    private static void staticFiles(HttpExchange exchange)
            throws IOException {

        String requestPath =
                exchange.getRequestURI().getPath();

        // Root URL → index.html
        if (requestPath.equals("/")) {
            requestPath = "/index.html";
        }

        // Remove leading /
        String relativePath =
                requestPath.startsWith("/")
                        ? requestPath.substring(1)
                        : requestPath;

        // Frontend directory
        Path frontendDirectory =
                Paths.get("frontend")
                        .toAbsolutePath()
                        .normalize();

        Path requestedFile =
                frontendDirectory
                        .resolve(relativePath)
                        .normalize();

        // =========================
        // PREVENT PATH TRAVERSAL
        // =========================

        if (!requestedFile.startsWith(frontendDirectory)) {

            sendTextResponse(
                    exchange,
                    403,
                    "Forbidden"
            );

            return;
        }

        // =========================
        // CHECK FILE
        // =========================

        if (!Files.exists(requestedFile) ||
                !Files.isRegularFile(requestedFile)) {

            sendTextResponse(
                    exchange,
                    404,
                    "Page not found"
            );

            return;
        }

        // =========================
        // CONTENT TYPE
        // =========================

        String contentType =
                getContentType(requestedFile);

        exchange.getResponseHeaders().set(
                "Content-Type",
                contentType
        );

        byte[] fileBytes =
                Files.readAllBytes(requestedFile);

        exchange.sendResponseHeaders(
                200,
                fileBytes.length
        );

        try (
                OutputStream outputStream =
                        exchange.getResponseBody()
        ) {

            outputStream.write(fileBytes);
        }
    }


    // =====================================================
    // CONTENT TYPE HELPER
    // =====================================================

    private static String getContentType(Path file) {

        String fileName =
                file.getFileName()
                        .toString()
                        .toLowerCase();

        if (fileName.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }

        if (fileName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }

        if (fileName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }

        if (fileName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }

        if (fileName.endsWith(".png")) {
            return "image/png";
        }

        if (fileName.endsWith(".jpg") ||
                fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }

        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }

        if (fileName.endsWith(".ico")) {
            return "image/x-icon";
        }

        return "application/octet-stream";
    }


    // =====================================================
    // TEXT RESPONSE
    // =====================================================

    private static void sendTextResponse(
            HttpExchange exchange,
            int statusCode,
            String message
    ) throws IOException {

        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/plain; charset=UTF-8"
        );

        byte[] responseBytes =
                message.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(
                statusCode,
                responseBytes.length
        );

        try (
                OutputStream outputStream =
                        exchange.getResponseBody()
        ) {

            outputStream.write(responseBytes);
        }
    }


    // =====================================================
    // JSON ESCAPE HELPER
    // =====================================================

    private static String escapeJson(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }


    // =====================================================
    // FORM DATA PARSER
    // =====================================================

    private static Map<String, String> parseFormData(
            String body) {

        Map<String, String> data =
                new HashMap<>();

        if (body == null ||
                body.isEmpty()) {

            return data;
        }

        String[] pairs =
                body.split("&");

        for (String pair : pairs) {

            String[] keyValue =
                    pair.split("=", 2);

            if (keyValue.length == 2) {

                String key =
                        URLDecoder.decode(
                                keyValue[0],
                                StandardCharsets.UTF_8
                        );

                String value =
                        URLDecoder.decode(
                                keyValue[1],
                                StandardCharsets.UTF_8
                        );

                data.put(
                        key,
                        value
                );
            }
        }

        return data;
    }


    // =====================================================
    // SEND JSON RESPONSE
    // =====================================================

    private static void sendResponse(
            HttpExchange exchange,
            int statusCode,
            String response
    ) throws IOException {

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin",
                "*"
        );

        byte[] responseBytes =
                response.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.sendResponseHeaders(
                statusCode,
                responseBytes.length
        );

        try (
                OutputStream outputStream =
                        exchange.getResponseBody()
        ) {

            outputStream.write(
                    responseBytes
            );
        }
    }
}