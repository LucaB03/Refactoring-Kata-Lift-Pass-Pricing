package dojo.liftpasspricing;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spark.Spark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization tests for the Lift Pass Pricing API.
 *
 * These tests capture the CURRENT behavior of Prices.java exactly as-is,
 * including any quirks or potential bugs. They serve as a safety net for
 * refactoring: if any test breaks after a code change, behavior has changed.
 *
 * Database seed data (from initDatabase.sql):
 *   base_price: '1jour' = 35, 'night' = 19
 *   holidays:   2019-02-18, 2019-02-25, 2019-03-04
 *
 * Key dates used in tests:
 *   2019-02-18  Monday + holiday
 *   2019-02-25  Monday + holiday
 *   2019-03-04  Monday + holiday
 *   2019-02-11  Monday, NOT a holiday  (reduction = 35)
 *   2019-02-13  Wednesday, NOT a holiday (reduction = 0)
 *   2019-02-22  Friday, NOT a holiday    (reduction = 0)
 */
public class PricesTest {

    private static Connection connection;

    @BeforeAll
    public static void createPrices() throws SQLException {
        connection = Prices.createApp();
        ensureSeedData();
    }

    @AfterAll
    public static void stopApplication() throws SQLException {
        Spark.stop();
        connection.close();
    }

    /**
     * Reset the base prices to known seed values before tests run,
     * so tests are deterministic regardless of execution order.
     */
    private static void ensureSeedData() throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO base_price (type, cost) VALUES (?, ?) ON DUPLICATE KEY UPDATE cost = ?")) {
            stmt.setString(1, "1jour");
            stmt.setInt(2, 35);
            stmt.setInt(3, 35);
            stmt.execute();

            stmt.setString(1, "night");
            stmt.setInt(2, 19);
            stmt.setInt(3, 19);
            stmt.execute();
        }
    }

    // ---------------------------------------------------------------
    // Helper to build GET /prices requests
    // ---------------------------------------------------------------

    private static RequestSpecification givenRequest() {
        return RestAssured.given().port(4567);
    }

    private static int getPriceFor(String type, Integer age, String date) {
        RequestSpecification spec = givenRequest().queryParam("type", type);
        if (age != null) {
            spec = spec.queryParam("age", age);
        }
        if (date != null) {
            spec = spec.queryParam("date", date);
        }
        JsonPath json = spec
                .when().get("/prices")
                .then().assertThat().statusCode(200)
                .assertThat().contentType("application/json")
                .extract().jsonPath();
        return json.getInt("cost");
    }

    // ---------------------------------------------------------------
    // PUT /prices - setting base prices
    // ---------------------------------------------------------------

    @Nested
    class PutPrices {

        @Test
        public void canSetDayPassPrice() throws SQLException {
            givenRequest()
                .queryParam("type", "1jour")
                .queryParam("cost", 40)
                .when().put("/prices")
                .then().assertThat().statusCode(200);

            // Verify the price was updated
            int cost = getPriceFor("1jour", 25, null);
            assertEquals(40, cost);

            // Reset to seed value for other tests
            ensureSeedData();
        }

        @Test
        public void canSetNightPassPrice() throws SQLException {
            givenRequest()
                .queryParam("type", "night")
                .queryParam("cost", 25)
                .when().put("/prices")
                .then().assertThat().statusCode(200);

            int cost = getPriceFor("night", 25, null);
            assertEquals(25, cost);

            // Reset to seed value
            ensureSeedData();
        }
    }

    // ===============================================================
    //  AGE < 6: always free, regardless of type, date, or holiday
    // ===============================================================

    @Nested
    class ChildUnder6 {

        @Test
        public void dayPass_age5_noDate() {
            assertEquals(0, getPriceFor("1jour", 5, null));
        }

        @Test
        public void dayPass_age0_noDate() {
            assertEquals(0, getPriceFor("1jour", 0, null));
        }

        @Test
        public void dayPass_age5_regularDay() {
            assertEquals(0, getPriceFor("1jour", 5, "2019-02-13"));
        }

        @Test
        public void dayPass_age5_monday() {
            assertEquals(0, getPriceFor("1jour", 5, "2019-02-11"));
        }

        @Test
        public void dayPass_age5_holiday() {
            assertEquals(0, getPriceFor("1jour", 5, "2019-02-18"));
        }

        @Test
        public void nightPass_age5_noDate() {
            assertEquals(0, getPriceFor("night", 5, null));
        }

        @Test
        public void nightPass_age3_noDate() {
            assertEquals(0, getPriceFor("night", 3, null));
        }
    }

    // ===============================================================
    //  NIGHT PASS: no day-of-week / holiday reductions apply
    //  base cost = 19
    // ===============================================================

    @Nested
    class NightPass {

        // -- Age null: falls into else branch -> cost = 0 --

        @Test
        public void nightPass_ageNull_noDate() {
            assertEquals(0, getPriceFor("night", null, null));
        }

        @Test
        public void nightPass_ageNull_withDate() {
            assertEquals(0, getPriceFor("night", null, "2019-02-13"));
        }

        // -- Age 6-64: full night price --

        @Test
        public void nightPass_age6() {
            assertEquals(19, getPriceFor("night", 6, null));
        }

        @Test
        public void nightPass_age15() {
            assertEquals(19, getPriceFor("night", 15, null));
        }

        @Test
        public void nightPass_age25() {
            assertEquals(19, getPriceFor("night", 25, null));
        }

        @Test
        public void nightPass_age64() {
            assertEquals(19, getPriceFor("night", 64, null));
        }

        // -- Age > 64: night cost * 0.4 -> ceil(19 * 0.4) = ceil(7.6) = 8 --

        @Test
        public void nightPass_age65() {
            assertEquals(8, getPriceFor("night", 65, null));
        }

        @Test
        public void nightPass_age80() {
            assertEquals(8, getPriceFor("night", 80, null));
        }

        // -- Night passes ignore date/holiday entirely --

        @Test
        public void nightPass_age25_monday() {
            assertEquals(19, getPriceFor("night", 25, "2019-02-11"));
        }

        @Test
        public void nightPass_age25_holiday() {
            assertEquals(19, getPriceFor("night", 25, "2019-02-18"));
        }

        @Test
        public void nightPass_age65_monday() {
            assertEquals(8, getPriceFor("night", 65, "2019-02-11"));
        }

        @Test
        public void nightPass_age65_holiday() {
            assertEquals(8, getPriceFor("night", 65, "2019-02-18"));
        }
    }

    // ===============================================================
    //  DAY PASS ("1jour"): base cost = 35
    //  Reduction: 35% on non-holiday Mondays, 0% otherwise
    // ===============================================================

    // ---------------------------------------------------------------
    //  Day pass, CHILD age 6-14: cost * 0.7
    //  NOTE: current code does NOT apply day-of-week reduction
    //  to children -- reduction is computed but the child branch
    //  returns before using it. ceil(35 * 0.7) = ceil(24.5) = 25
    // ---------------------------------------------------------------

    @Nested
    class DayPassChild {

        @Test
        public void dayPass_age6_noDate() {
            // ceil(35 * 0.7) = ceil(24.5) = 25
            assertEquals(25, getPriceFor("1jour", 6, null));
        }

        @Test
        public void dayPass_age14_noDate() {
            assertEquals(25, getPriceFor("1jour", 14, null));
        }

        @Test
        public void dayPass_age10_regularDay() {
            // Child discount applied, no Monday reduction used
            assertEquals(25, getPriceFor("1jour", 10, "2019-02-13"));
        }

        @Test
        public void dayPass_age10_mondayNonHoliday() {
            // Even on a Monday, children get flat 0.7 multiplier
            // reduction=35 is calculated but NOT applied to child branch
            assertEquals(25, getPriceFor("1jour", 10, "2019-02-11"));
        }

        @Test
        public void dayPass_age10_holiday() {
            assertEquals(25, getPriceFor("1jour", 10, "2019-02-18"));
        }

        @Test
        public void dayPass_age10_mondayHoliday() {
            // 2019-02-18 is both a Monday and a holiday
            // Since it's a holiday, no Monday reduction -> but child ignores it anyway
            assertEquals(25, getPriceFor("1jour", 10, "2019-02-18"));
        }
    }

    // ---------------------------------------------------------------
    //  Day pass, ADULT age 15-64: cost * (1 - reduction/100)
    //  No date or regular day: reduction=0 -> 35
    //  Non-holiday Monday:     reduction=35 -> ceil(35 * 0.65) = ceil(22.75) = 23
    //  Holiday (even Monday):  reduction=0  -> 35
    // ---------------------------------------------------------------

    @Nested
    class DayPassAdult {

        @Test
        public void dayPass_age15_noDate() {
            // 35 * (1 - 0/100) = 35
            assertEquals(35, getPriceFor("1jour", 15, null));
        }

        @Test
        public void dayPass_age25_noDate() {
            assertEquals(35, getPriceFor("1jour", 25, null));
        }

        @Test
        public void dayPass_age64_noDate() {
            assertEquals(35, getPriceFor("1jour", 64, null));
        }

        @Test
        public void dayPass_age25_regularWeekday() {
            // Wednesday, not a holiday -> reduction=0 -> 35
            assertEquals(35, getPriceFor("1jour", 25, "2019-02-13"));
        }

        @Test
        public void dayPass_age25_friday() {
            assertEquals(35, getPriceFor("1jour", 25, "2019-02-22"));
        }

        @Test
        public void dayPass_age25_mondayNonHoliday() {
            // Monday 2019-02-11, not a holiday -> reduction=35
            // ceil(35 * (1 - 35/100)) = ceil(35 * 0.65) = ceil(22.75) = 23
            assertEquals(23, getPriceFor("1jour", 25, "2019-02-11"));
        }

        @Test
        public void dayPass_age25_holiday() {
            // 2019-02-18 is a holiday -> isHoliday=true -> no Monday reduction
            // 35 * (1 - 0/100) = 35
            assertEquals(35, getPriceFor("1jour", 25, "2019-02-18"));
        }

        @Test
        public void dayPass_age25_mondayHoliday() {
            // 2019-02-18 is both Monday AND holiday
            // Holiday takes precedence -> reduction=0 -> 35
            assertEquals(35, getPriceFor("1jour", 25, "2019-02-18"));
        }

        @Test
        public void dayPass_age25_secondHoliday() {
            // 2019-02-25 is also a Monday + holiday
            assertEquals(35, getPriceFor("1jour", 25, "2019-02-25"));
        }

        @Test
        public void dayPass_age25_thirdHoliday() {
            // 2019-03-04 is also a Monday + holiday
            assertEquals(35, getPriceFor("1jour", 25, "2019-03-04"));
        }
    }

    // ---------------------------------------------------------------
    //  Day pass, SENIOR age > 64: cost * 0.75 * (1 - reduction/100)
    //  No date or regular day: ceil(35 * 0.75) = ceil(26.25) = 27
    //  Non-holiday Monday:     ceil(35 * 0.75 * 0.65) = ceil(17.0625) = 18
    //  Holiday:                ceil(35 * 0.75) = 27
    // ---------------------------------------------------------------

    @Nested
    class DayPassSenior {

        @Test
        public void dayPass_age65_noDate() {
            // ceil(35 * 0.75 * 1.0) = ceil(26.25) = 27
            assertEquals(27, getPriceFor("1jour", 65, null));
        }

        @Test
        public void dayPass_age80_noDate() {
            assertEquals(27, getPriceFor("1jour", 80, null));
        }

        @Test
        public void dayPass_age65_regularWeekday() {
            // Wednesday -> reduction=0 -> ceil(35 * 0.75) = 27
            assertEquals(27, getPriceFor("1jour", 65, "2019-02-13"));
        }

        @Test
        public void dayPass_age65_mondayNonHoliday() {
            // Monday 2019-02-11 -> reduction=35
            // ceil(35 * 0.75 * 0.65) = ceil(17.0625) = 18
            assertEquals(18, getPriceFor("1jour", 65, "2019-02-11"));
        }

        @Test
        public void dayPass_age65_holiday() {
            // 2019-02-18 holiday -> reduction=0 -> ceil(35 * 0.75) = 27
            assertEquals(27, getPriceFor("1jour", 65, "2019-02-18"));
        }

        @Test
        public void dayPass_age65_mondayHoliday() {
            // Holiday takes precedence -> no Monday reduction -> 27
            assertEquals(27, getPriceFor("1jour", 65, "2019-02-18"));
        }
    }

    // ---------------------------------------------------------------
    //  Day pass, AGE NULL: cost * (1 - reduction/100)
    //  Same formula as adult, but age param is absent
    //  No date or regular day: 35
    //  Non-holiday Monday:     ceil(35 * 0.65) = ceil(22.75) = 23
    //  Holiday:                35
    // ---------------------------------------------------------------

    @Nested
    class DayPassAgeNull {

        @Test
        public void dayPass_ageNull_noDate() {
            assertEquals(35, getPriceFor("1jour", null, null));
        }

        @Test
        public void dayPass_ageNull_regularWeekday() {
            assertEquals(35, getPriceFor("1jour", null, "2019-02-13"));
        }

        @Test
        public void dayPass_ageNull_mondayNonHoliday() {
            // ceil(35 * 0.65) = ceil(22.75) = 23
            assertEquals(23, getPriceFor("1jour", null, "2019-02-11"));
        }

        @Test
        public void dayPass_ageNull_holiday() {
            assertEquals(35, getPriceFor("1jour", null, "2019-02-18"));
        }

        @Test
        public void dayPass_ageNull_mondayHoliday() {
            assertEquals(35, getPriceFor("1jour", null, "2019-02-18"));
        }
    }

    // ===============================================================
    //  BOUNDARY TESTS: exact age boundaries
    // ===============================================================

    @Nested
    class AgeBoundaries {

        // -- Boundary at age 6: under-6 is free, 6+ is not --

        @Test
        public void dayPass_age5_isFree() {
            assertEquals(0, getPriceFor("1jour", 5, null));
        }

        @Test
        public void dayPass_age6_isChild() {
            // ceil(35 * 0.7) = 25 (child rate, not free)
            assertEquals(25, getPriceFor("1jour", 6, null));
        }

        // -- Boundary at age 15: child vs adult for day pass --

        @Test
        public void dayPass_age14_isChild() {
            assertEquals(25, getPriceFor("1jour", 14, null));
        }

        @Test
        public void dayPass_age15_isAdult() {
            assertEquals(35, getPriceFor("1jour", 15, null));
        }

        // -- Boundary at age 64/65: adult vs senior for day pass --

        @Test
        public void dayPass_age64_isAdult() {
            assertEquals(35, getPriceFor("1jour", 64, null));
        }

        @Test
        public void dayPass_age65_isSenior() {
            // ceil(35 * 0.75) = 27
            assertEquals(27, getPriceFor("1jour", 65, null));
        }

        // -- Night pass boundary at age 6: under-6 free vs 6+ full --

        @Test
        public void nightPass_age5_isFree() {
            assertEquals(0, getPriceFor("night", 5, null));
        }

        @Test
        public void nightPass_age6_isFullPrice() {
            assertEquals(19, getPriceFor("night", 6, null));
        }

        // -- Night pass boundary at age 64/65: full vs senior --

        @Test
        public void nightPass_age64_isFullPrice() {
            assertEquals(19, getPriceFor("night", 64, null));
        }

        @Test
        public void nightPass_age65_isSenior() {
            // ceil(19 * 0.4) = ceil(7.6) = 8
            assertEquals(8, getPriceFor("night", 65, null));
        }
    }

    // ===============================================================
    //  RESPONSE FORMAT: verify JSON structure matches exactly
    // ===============================================================

    @Nested
    class ResponseFormat {

        @Test
        public void responseIsJson() {
            givenRequest()
                .queryParam("type", "1jour")
                .queryParam("age", 25)
                .when().get("/prices")
                .then()
                .assertThat().statusCode(200)
                .assertThat().contentType("application/json");
        }

        @Test
        public void responseCostFieldIsInteger() {
            JsonPath json = givenRequest()
                .queryParam("type", "1jour")
                .queryParam("age", 25)
                .when().get("/prices")
                .then().extract().jsonPath();

            // Verify the cost field exists and is a number
            int cost = json.getInt("cost");
            assertEquals(35, cost);
        }

        @Test
        public void putReturnsEmptyBody() {
            String body = givenRequest()
                .queryParam("type", "1jour")
                .queryParam("cost", 35)
                .when().put("/prices")
                .then()
                .assertThat().statusCode(200)
                .extract().body().asString();

            assertEquals("", body);
        }
    }
}
