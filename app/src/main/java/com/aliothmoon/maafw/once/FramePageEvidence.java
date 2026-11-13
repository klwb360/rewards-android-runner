package com.aliothmoon.maafw.once;
import java.util.*;
import io.github.klwb360.rewards.core.BingDailySetPage;
import io.github.klwb360.rewards.core.RewardsCore;
/** Single-image OCR text observations, never a reconstructed UI tree or task authority. */
final class FramePageEvidence {
 enum Brand { SAME_FRAME_BING_TEXT_CANDIDATE, NOT_OBSERVED }
 enum Scope { DAILY_SET_TEXT_IN_FRAME_UNBOUND, UNKNOWN }
 enum Boundary { OCR_ROW_ONLY_CARD_UNESTABLISHED }
 enum Completeness { REJECTED, TEXT_ONLY_INCOMPLETE }
 record Offer(int row,int x,int y,int width,int height,int titleCodePoints,int points,
              BingDailySetPage.TextStatus status,Boundary boundary) {}
 record Result(Brand brand,Scope scope,Completeness completeness,List<Offer> offers,RewardsCore.ParseStatus strictCards){
  Result { offers=List.copyOf(offers); }
  static Result rejected(){return new Result(Brand.NOT_OBSERVED,Scope.UNKNOWN,Completeness.REJECTED,List.of(),RewardsCore.ParseStatus.MISSING);}
 }
 static Result parse(FrameStructureEvidence.Binding actual,FrameStructureEvidence.Binding expected,
                     List<FrameStructureEvidence.Line> lines,int width,int height){
  OcrRowsAssessment quality=OcrRowsAssessment.assess(actual,expected,lines,width,height);
  if(!quality.shapeValid()||quality.nonempty()==0)return Result.rejected();
  if(quality.lowConfidence()>0){
   // Independent high-confidence brand text may survive noise. Discarding rows
   // can never establish uniqueness, scopes, offers, counters or complete cards.
   Brand candidate=Brand.NOT_OBSERVED;
   for(var row:lines)if(row.score()>=0.8){String text=RewardsCore.normalize(row.text());
    if(text.equals("bing")||text.equals("microsoft bing"))candidate=Brand.SAME_FRAME_BING_TEXT_CANDIDATE;}
   return candidate==Brand.NOT_OBSERVED?Result.rejected():new Result(candidate,Scope.UNKNOWN,
     Completeness.TEXT_ONLY_INCOMPLETE,List.of(),RewardsCore.ParseStatus.MISSING);
  }
  Brand brand=Brand.NOT_OBSERVED;int headings=0;List<Offer> offers=new ArrayList<>();Set<String> labels=new HashSet<>();
  List<RewardsCore.Node> flat=new ArrayList<>();
  for(int i=0;i<lines.size();i++){
   var row=lines.get(i);String text=RewardsCore.normalize(row.text());
   if(text.contains("sign in")||text.contains("登录")||text.contains("登入"))return Result.rejected();
   if(text.equals("bing")||text.equals("microsoft bing"))brand=Brand.SAME_FRAME_BING_TEXT_CANDIDATE;
   if(text.equals("daily set")||text.equals("每日活动"))headings++;
   // Flat roots preserve the actual lack of observed parenthood and clickability.
   flat.add(new RewardsCore.Node("row"+i,"",row.text(),"",false,false,true));
   var label=BingDailySetPage.observeOfferText(text);
   if(label.isPresent()){
    if(offers.size()==8||!labels.add(text))return Result.rejected();
    var value=label.get();
    offers.add(new Offer(i,row.x(),row.y(),row.width(),row.height(),value.titleCodePoints(),value.points(),value.status(),Boundary.OCR_ROW_ONLY_CARD_UNESTABLISHED));
   } else if(text.contains("points")||text.contains("积分")) {
    // Unrecognized points text is not an offer, completion or credited balance.
   }
  }
  // Do not invent parent groups, enabled/clickable bits or nested card labels for the strict parser.
  var strict=BingDailySetPage.parse(flat);
  return new Result(brand,headings==1?Scope.DAILY_SET_TEXT_IN_FRAME_UNBOUND:Scope.UNKNOWN,
    Completeness.TEXT_ONLY_INCOMPLETE,offers,strict.status());
 }
 static String summary(Result result){
  if(result==null)return "Same-frame evidence: NOT_OBSERVED";
  StringBuilder text=new StringBuilder("Brand=").append(result.brand()).append("; scope=").append(result.scope())
   .append("; completeness=").append(result.completeness()).append("; strict cards=").append(result.strictCards());
  for(Offer offer:result.offers())text.append("\nOffer row=").append(offer.row()).append(" box=").append(offer.x()).append(',').append(offer.y()).append(',').append(offer.width()).append(',').append(offer.height())
   .append(" titleCodePoints=").append(offer.titleCodePoints()).append(" explicitPoints=").append(offer.points()).append(" textStatus=").append(offer.status()).append(" boundary=").append(offer.boundary());
  return text.append("\nNo complete cards, account state, credited increase or action causality established. Actor remains unbound.").toString();
 }
}
