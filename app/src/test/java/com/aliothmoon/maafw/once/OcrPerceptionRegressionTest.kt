package com.aliothmoon.maafw.once

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import io.github.klwb360.rewards.core.RewardsCore

/** Invented bounded OCR detail only. No real frame/OCR/account observations. */
class OcrPerceptionRegressionTest {
    private val binding = FrameStructureEvidence.Binding(Any(),Any(),Any(),Any())
    private fun row(label: String, score: Double = .95, x: Int=1, width: Int=250) = buildJsonObject {
        put("text",label);put("score",score);put("box",buildJsonArray {add(x);add(1);add(width);add(20)})
    }
    private fun decode(vararg rows: JsonElement): OcrDetailDecoder.Result = raw(buildJsonObject {put("all",JsonArray(rows.toList()))}.toString())
    private fun raw(value: String) = OcrDetailDecoder.decode(value.toByteArray(),binding,400,800)
    // JVM regex checks catalogue intent, not native OCR or C++ regex equivalence.
    private fun observation(vararg rows: String): RewardsObservation {
        val hits=RewardsQuery.values().map { query ->
            val pattern=Json.parseToJsonElement(query.parameters()).jsonObject["expected"]!!.jsonArray[0].jsonPrimitive.content.toRegex()
            rows.any { pattern.containsMatchIn(it) }
        }
        return RewardsObservation.classify(hits[0],hits[1],hits[2],hits[3])
    }
    @Test fun recoveredChineseDailyAliasActuallyChangesCoarseCandidate() {
        val before = "[Dd][Aa][Ii][Ll][Yy][ ]*[Ss][Ee][Tt]|每日任务|每日集".toRegex()
        assertFalse(before.containsMatchIn("每日活动"))
        for (heading in listOf("Daily set","每日活动","网页每日活动","每日任务","每日集")) {
            assertEquals(heading,RewardsObservation.Page.REWARDS_HOME,observation("Rewards",heading).page())
        }
        assertEquals(RewardsObservation.Page.UNKNOWN,observation("每日活动").page())
        assertEquals(RewardsObservation.Page.UNKNOWN,observation("积分 999").page())
    }
    @Test fun homeSignInDailyProgressNonRewardsAndOwnWaitingFixtures() {
        assertEquals(RewardsObservation.Page.REWARDS_HOME,observation("Rewards","Daily set").page())
        assertEquals(RewardsObservation.Page.SIGN_IN_NEEDED,observation("微软奖励","登录").page())
        assertEquals(RewardsObservation.Page.CONFLICT,observation("Rewards","Sign in","Progress").page())
        assertEquals(RewardsObservation.Page.REWARDS_HOME,observation("Rewards","Points earned").page())
        assertEquals(RewardsObservation.Page.UNKNOWN,observation("Rewards").page())
        assertEquals(RewardsObservation.Page.UNKNOWN,observation("Bing","weather").page())
        assertEquals(RewardsObservation.Page.UNKNOWN,observation("Rewards只读","等待 Android 系统同意").page())
        assertEquals(TargetPageReadiness.Decision.NOT_ESTABLISHED,TargetPageReadiness.evaluate(observation("Rewards","Daily set"),true))
        assertEquals(TargetPageReadiness.Decision.REJECT_OWN_UI,TargetPageReadiness.evaluate(observation("Rewards","Daily set"),false))
    }
    @Test fun recoveredCheckInTextDoesNotProveSignInOrCompletion() {
        // The legacy Check in grammar also needs hierarchy, state icons and seven-day rows.
        for (heading in listOf("Check in","签入","签到","已签到","已完成")) {
            assertEquals(RewardsObservation.Page.UNKNOWN,observation("Rewards",heading,"Streaks").page())
            val result=decode(row("Bing"),row("Rewards"),row(heading),row("Day 1"))
            assertFalse(result.structure.supported());assertTrue(result.page.offers().isEmpty())
            assertNotEquals(RewardsCore.ParseStatus.OK,result.page.strictCards())
        }
    }
    @Test fun bingBrandAndRewardsMarkerAreDifferentSignals() {
        assertEquals(FramePageEvidence.Brand.NOT_OBSERVED,decode(row("Rewards"),row("Daily set")).page.brand())
        assertEquals(RewardsObservation.Page.UNKNOWN,observation("Bing").page())
        assertEquals(FramePageEvidence.Brand.SAME_FRAME_BING_TEXT_CANDIDATE,decode(row("Bing")).page.brand())
    }
    @Test fun validRowsReuseRecoveredGrammarWithoutInventingCards() {
        val result=decode(row("Bing"),row("Daily set"),row("Alpha, brief, earn 10 points"),row("Beta, 10 points earned"))
        assertTrue(result.transportAccepted());assertEquals(2,result.page.offers().size)
        assertNotEquals(RewardsCore.ParseStatus.OK,result.page.strictCards())
        assertEquals(FramePageEvidence.Completeness.TEXT_ONLY_INCOMPLETE,result.page.completeness())
        assertFalse(result.summary().contains("Alpha"));assertFalse(result.summary().contains("Beta"))
    }
    @Test fun lowConfidenceNoiseOnlyPreservesIndependentBrand() {
        for (noise in listOf("unrelated","Sign in","Daily set","Search points 0/90")) {
            val result=decode(row("Bing"),row("Daily set"),row("A, 10 points earned"),row(noise,.79))
            assertEquals(1,result.quality!!.lowConfidence());assertEquals(FramePageEvidence.Brand.SAME_FRAME_BING_TEXT_CANDIDATE,result.page.brand())
            assertEquals(FramePageEvidence.Scope.UNKNOWN,result.page.scope());assertTrue(result.page.offers().isEmpty())
            assertEquals(RewardsCore.ParseStatus.MISSING,result.page.strictCards());assertFalse(result.structure.supported())
        }
        assertEquals(FramePageEvidence.Brand.NOT_OBSERVED,decode(row("Bing",.79),row("Daily set")).page.brand())
    }
    @Test fun strictCounterDoesNotSkipLowConfidenceOrConflictingRows() {
        assertTrue(decode(row("Search points 10/90")).structure.supported())
        assertFalse(decode(row("Search points 10/90"),row("noise",.79)).structure.supported())
        assertFalse(decode(row("Search points 10/90"),row("Search points 20/90")).structure.supported())
        assertFalse(decode(row("Search points 10/90"),row("Sign in")).structure.supported())
        assertFalse(decode(row("Search points 100/90")).structure.supported())
    }
    @Test fun noTextAndBlankRowsAreExplicitAndRejectEvidence() {
        val empty=decode();assertEquals(OcrDetailDecoder.Parse.EMPTY,empty.parse);assertTrue(empty.transportAccepted())
        assertEquals(OcrRowsAssessment.Reason.EMPTY_ROWS,empty.quality!!.reason());assertFalse(empty.structure.supported())
        for(text in listOf("   ","\t\n","\u2003")){
            val blank=decode(row(text));assertEquals(0,blank.quality!!.nonempty())
            assertEquals(FramePageEvidence.Completeness.REJECTED,blank.page.completeness())
        }
    }
    @Test fun lowInvalidScoresAndBoxesFailClosedWithDifferentReasons() {
        assertEquals(OcrRowsAssessment.Reason.SCORE,decode(row("Bing",1.01)).quality!!.reason())
        assertEquals(OcrRowsAssessment.Reason.SCORE,decode(row("Bing",-.1)).quality!!.reason())
        assertEquals(OcrRowsAssessment.Reason.BOX,decode(row("Bing",x=399,width=2)).quality!!.reason())
        assertEquals(FramePageEvidence.Completeness.REJECTED,decode(row("Bing"),row("noise",width=0)).page.completeness())
        assertEquals(OcrDetailDecoder.Parse.ROW_SHAPE,decode(row("Bing",x=-1)).parse)
    }
    @Test fun truncatedJsonInvalidUtf8AndShapeDoNotBecomeNoText() {
        for (text in listOf("{\"all\":[", "{\"all\":[{", "{\"all\":[null]}")) assertFalse(raw(text).transportAccepted())
        assertEquals(OcrDetailDecoder.Parse.ROOT,raw("[]").parse)
        assertEquals(OcrDetailDecoder.Parse.ALL,raw("{}").parse)
        assertEquals(OcrDetailDecoder.Parse.ALL,raw("{\"all\":{}}").parse)
        assertEquals(OcrDetailDecoder.Parse.UTF8,OcrDetailDecoder.decode(byteArrayOf(0xc3.toByte(),0x28),binding,400,800).parse)
        val malformed=buildJsonObject {put("text",1);put("score",.9);put("box",buildJsonArray {add(0);add(0);add(1);add(1)})}
        assertEquals(OcrDetailDecoder.Parse.ROW_SHAPE,decode(malformed).parse)
    }
    @Test fun duplicateRootRowAndEscapedKeysAreExplicitlyRejected() {
        assertEquals(OcrDetailDecoder.Parse.DUPLICATE_KEY,raw("""{"all":[],"all":[]}""").parse)
        assertEquals(OcrDetailDecoder.Parse.DUPLICATE_KEY,raw("""{"all":[{"text":"Bing","score":0.9,"score":0.1,"box":[0,0,20,20]}]}""").parse)
        assertEquals(OcrDetailDecoder.Parse.DUPLICATE_KEY,raw("""{"all":[],"\u0061ll":[]}""").parse)
        assertEquals(OcrDetailDecoder.Parse.OK,decode(row("string with \"all\": and bracket ]")).parse)
    }
    @Test fun boundsAreRejectedNeverSilentlyTruncated() {
        assertEquals(OcrDetailDecoder.Parse.SIZE,raw(" ".repeat(65537)).parse)
        assertEquals(OcrDetailDecoder.Parse.ROW_LIMIT,decode(*Array(129){row("x")}).parse)
        assertEquals(OcrRowsAssessment.Reason.TEXT_LIMIT,decode(row("x".repeat(513))).quality!!.reason())
        assertEquals(OcrDetailDecoder.Parse.DEPTH,raw("[".repeat(17)+"0"+"]".repeat(17)).parse)
        assertEquals(OcrDetailDecoder.Parse.OK,decode(row("quoted [ ] { } \" value")).parse)
    }
    @Test fun queryStatusesRetainEachHitAndSuppressBeforeReleaseOrCancellation() {
        val d=PerceptionDiagnostics();d.frame(400,800);d.decoded(decode(row("private-canary")))
        for(q in RewardsQuery.values()) {d.posted(q);d.terminal(q,if(q==RewardsQuery.DAILY_SET)3000 else 4000);d.detail(q,q==RewardsQuery.DAILY_SET)}
        assertNull(d.published(true,false,false));assertNull(d.published(true,true,true));assertNull(d.published(false,true,false))
        val summary=d.published(true,true,false)!!
        assertTrue(summary.contains("DAILY_SET=DETAIL_READ/3000/true"));assertTrue(summary.contains("REWARDS_MARKER=DETAIL_READ/4000/false"))
        assertFalse(summary.contains("private-canary"));assertTrue(summary.length<1400)
        assertThrows(IllegalStateException::class.java){d.posted(RewardsQuery.DAILY_SET)}
    }
    @Test fun globalInvalidShapeNeverPreservesBrand() {
        assertEquals(FramePageEvidence.Brand.NOT_OBSERVED,decode(row("Bing"),row("noise",1.1)).page.brand())
        val other=FrameStructureEvidence.Binding(Any(),Any(),Any(),Any())
        assertEquals(OcrRowsAssessment.Reason.BINDING,OcrRowsAssessment.assess(binding,other,listOf(FrameStructureEvidence.Line("Bing",0,0,20,20,.9)),400,800).reason())
    }
}
