package com.rgp.loadtest.silkroad.requests;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;

/**
 * HTTP request packaging for Silk Road slot endpoints. Each method returns a ChainBuilder that: -
 * Reads `userId` from the session (provided by feeder). - Loads body from
 * src/test/resources/bodies/*.json with EL templating. - Asserts HTTP 200.
 *
 * <p>Body templates use Gatling EL syntax (${userId} etc.) — see resources/bodies/.
 */
public final class SlotRequests {

  private SlotRequests() {}

  /** POST /api/game/caravans/v1/slot/spin */
  public static ChainBuilder spin() {
    return exec(
        http("spin")
            .post("/api/game/caravans/v1/slot/spin")
            .body(ElFileBody("games/silkroad/bodies/spin.json"))
            .check(status().is(200)));
  }

  /** POST /api/game/caravans/v1/slot/last-spin */
  public static ChainBuilder lastSpin() {
    return exec(
        http("last-spin")
            .post("/api/game/caravans/v1/slot/last-spin")
            .body(ElFileBody("games/silkroad/bodies/last-spin.json"))
            .check(status().is(200)));
  }

  /** POST /api/game/caravans/v1/slot/history/summary */
  public static ChainBuilder historySummary() {
    return exec(
        http("history-summary")
            .post("/api/game/caravans/v1/slot/history/summary")
            .body(ElFileBody("games/silkroad/bodies/history-summary.json"))
            .check(status().is(200)));
  }
}
