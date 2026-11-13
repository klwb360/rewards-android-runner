package com.aliothmoon.maafw.once;

import io.github.klwb360.rewards.core.RewardsCore;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic parser fixtures only: no native source, freshness or action authority. */
public class FrameTaskProgressEvidenceTest {
 private final FrameStructureEvidence.Binding binding = new FrameStructureEvidence.Binding(new Object(), new Object(), new Object(), new Object());
 private FrameStructureEvidence.Line line(String text) { return new FrameStructureEvidence.Line(text, 10, 10, 200, 20, 0.95); }
 private List<FrameStructureEvidence.Line> rows(String... texts) {
  List<FrameStructureEvidence.Line> result = new ArrayList<>();
  for (String text : texts) result.add(line(text));
  return result;
 }
 private FrameStructureEvidence.Result parse(RewardsCore.Task task, String... texts) {
  return FrameStructureEvidence.parseForTask(binding, binding, rows(texts), 300, 300, task);
 }
 private void counter(FrameStructureEvidence.Result r, RewardsCore.Task task, int current, int target) {
  assertTrue(r.supported()); assertEquals(task, r.task()); assertEquals(1, r.counters());
  assertEquals(current, r.current()); assertEquals(target, r.target());
 }
 @Test public void bothTasksProjectIndependentlyWithoutAggregationOrMutation() {
  var all = List.copyOf(rows("Search to earn 7/60", "Read to earn 4/30", "Daily set 1/3", "Balance 1000"));
  var snapshot = List.copyOf(all);
  counter(FrameStructureEvidence.parseForTask(binding, binding, all, 300, 300, RewardsCore.Task.SEARCH), RewardsCore.Task.SEARCH, 7, 60);
  counter(FrameStructureEvidence.parseForTask(binding, binding, all, 300, 300, RewardsCore.Task.READING), RewardsCore.Task.READING, 4, 30);
  assertEquals(snapshot, all);
  assertFalse(FrameStructureEvidence.parse(binding, binding, all, 300, 300).supported());
 }
 @Test public void chineseAndFullWidthEnglishUseExistingNormalization() {
  counter(parse(RewardsCore.Task.SEARCH, "搜索以赚取 ７／６０", "阅读以赚取 ４／３０"), RewardsCore.Task.SEARCH, 7, 60);
  counter(parse(RewardsCore.Task.READING, "搜索积分 ７／６０", "阅读以赚取 ４／３０"), RewardsCore.Task.READING, 4, 30);
  counter(parse(RewardsCore.Task.SEARCH, "Ｓｅａｒｃｈ ｔｏ ｅａｒｎ ７／６０"), RewardsCore.Task.SEARCH, 7, 60);
 }
 @Test public void duplicatesOfDesiredOrOtherRoleReject() {
  for (String duplicate : new String[]{"Search points 2/60", "Read to earn 2/30", "Daily set 2/3"}) {
   assertFalse(parse(RewardsCore.Task.SEARCH, "Search to earn 1/60", "Read to earn 1/30", "Daily set 1/3", duplicate).supported());
  }
 }
 @Test public void malformedDesiredAndOtherTaskFractionsReject() {
  for (String fraction : new String[]{"-1/30", "+1/30", "1/-30", "1/0", "31/30", "1 / 30", "1/30 extra", "2147483648/2147483648", "1.5/30", "1/"}) {
   assertFalse(fraction, parse(RewardsCore.Task.SEARCH, "Search to earn 1/60", "Read to earn " + fraction).supported());
   assertFalse(fraction, parse(RewardsCore.Task.READING, "Read to earn " + fraction).supported());
  }
 }
 @Test public void foreignFractionsAreNotTaskProgressAndVetoProjection() {
  for (String foreign : new String[]{"1/30", "Balance 1/30", "Countdown 1 / 30", "签到天数 1/7", "Other +1/-3", "余额 １／３０"}) {
   assertFalse(foreign, parse(RewardsCore.Task.SEARCH, "Search to earn 1/60", foreign).supported());
   assertFalse(foreign, parse(RewardsCore.Task.SEARCH, foreign).supported());
  }
 }
 @Test public void missingDesiredRoleSplitRowsAndUnsupportedRequestsReject() {
  assertFalse(parse(RewardsCore.Task.SEARCH, "Read to earn 1/30").supported());
  assertFalse(parse(RewardsCore.Task.READING, "Search to earn 1/60").supported());
  assertFalse(parse(RewardsCore.Task.SEARCH, "Search to earn", "1/60").supported());
  assertFalse(parse(RewardsCore.Task.WEB, "Daily set 1/3").supported());
  assertFalse(parse(null, "Search to earn 1/60").supported());
 }
 @Test public void staleOrDifferentBindingComponentsReject() {
  var alternatives = List.of(
   new FrameStructureEvidence.Binding(new Object(), binding.owner(), binding.epoch(), binding.frame()),
   new FrameStructureEvidence.Binding(binding.source(), new Object(), binding.epoch(), binding.frame()),
   new FrameStructureEvidence.Binding(binding.source(), binding.owner(), new Object(), binding.frame()),
   new FrameStructureEvidence.Binding(binding.source(), binding.owner(), binding.epoch(), new Object()));
  for (var other : alternatives) assertFalse(FrameStructureEvidence.parseForTask(binding, other, rows("Search to earn 1/60"), 300, 300, RewardsCore.Task.SEARCH).supported());
  assertFalse(FrameStructureEvidence.parseForTask(null, binding, rows("Search to earn 1/60"), 300, 300, RewardsCore.Task.SEARCH).supported());
 }
 @Test public void completionAndProgressKeepObservedTargetAndUnits() {
  counter(parse(RewardsCore.Task.SEARCH, "Search to earn 59/60"), RewardsCore.Task.SEARCH, 59, 60);
  counter(parse(RewardsCore.Task.SEARCH, "Search to earn 60/60"), RewardsCore.Task.SEARCH, 60, 60);
  counter(parse(RewardsCore.Task.READING, "Read to earn 17/17"), RewardsCore.Task.READING, 17, 17);
  counter(parse(RewardsCore.Task.READING, "Read to earn 0/17"), RewardsCore.Task.READING, 0, 17);
  assertEquals(FrameStructureEvidence.Unit.UNESTABLISHED, parse(RewardsCore.Task.SEARCH, "Search to earn 60/60").unit());
  assertEquals(FrameStructureEvidence.Unit.UNESTABLISHED, parse(RewardsCore.Task.READING, "Read to earn 17/17").unit());
  assertEquals(FrameStructureEvidence.Unit.OCR_TEXT_POINTS, parse(RewardsCore.Task.SEARCH, "搜索积分 60/60").unit());
 }
 @Test public void wholeFrameQualityIsValidatedBeforeRoleSelection() {
  for (var bad : new FrameStructureEvidence.Line[]{
   line("x".repeat(513)), new FrameStructureEvidence.Line("Unrelated", 10, 10, 20, 20, 0.79),
   new FrameStructureEvidence.Line("Unrelated", 10, 10, 20, 20, Double.NaN),
   new FrameStructureEvidence.Line("Unrelated", 10, 10, 20, 20, 1.01),
   new FrameStructureEvidence.Line("Unrelated", 299, 10, 20, 20, 0.95), null}) {
   var all = rows("Search to earn 1/60"); all.add(bad);
   assertFalse(FrameStructureEvidence.parseForTask(binding, binding, all, 300, 300, RewardsCore.Task.SEARCH).supported());
  }
  var tooMany = rows("Search to earn 1/60");
  for (int i = 0; i < 128; i++) tooMany.add(line("Unrelated"));
  assertFalse(FrameStructureEvidence.parseForTask(binding, binding, tooMany, 300, 300, RewardsCore.Task.SEARCH).supported());
 }
 @Test public void loginAnywhereVetoesProjection() {
  for (String login : new String[]{"Sign in", "登录", "登入"}) assertFalse(parse(RewardsCore.Task.SEARCH, "Search to earn 1/60", login).supported());
 }
 @Test public void legacyApiRetainsExistingSingleTaskBehavior() {
  counter(FrameStructureEvidence.parse(binding, binding, rows("Search to earn 1/60", "Unrelated 2/5"), 300, 300), RewardsCore.Task.SEARCH, 1, 60);
  assertFalse(FrameStructureEvidence.parse(binding, binding, rows("Search to earn 1/60", "Read to earn 1/30"), 300, 300).supported());
 }
}
