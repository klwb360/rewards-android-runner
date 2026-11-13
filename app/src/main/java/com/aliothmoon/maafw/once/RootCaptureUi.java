package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Private reviewed root profile. Buttons only deliver real visible user clicks. */
final class RootCaptureUi {
    private RootCaptureUi() { }
    private static LinearLayout page(Activity activity,String text){
        ScrollView scroll=new ScrollView(activity);LinearLayout content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*activity.getResources().getDisplayMetrics().density);
        content.setPadding(p,p,p,p);TextView label=new TextView(activity);label.setText(text);label.setTextSize(16);
        label.setTextIsSelectable(true);content.addView(label);scroll.addView(content);activity.setContentView(scroll);return content;
    }
    private static void button(Activity activity,LinearLayout content,String text,boolean enabled,Runnable action){
        Button button=new Button(activity);button.setText(text);button.setEnabled(enabled);
        button.setOnClickListener(view->{if(button.isEnabled()){button.setEnabled(false);action.run();}});content.addView(button);
    }
    static final String UNIQUE_PENDING_EXPLANATION="Root 任务卡原生验证 v38：唯一待完成项\n"
        +"2026-11-12 05:20 至 07:00 UTC。一次新的可见批准、一次隐藏自身；会话180秒，BEFORE与AFTER各90秒，不因再观察延期。\n"
        +"安全匹配诊断不包含OCR原文；仅依据本轮新帧，不改写旧回执。\n"
        +"按卡片边框定位，文字辅助识别风险与禁用项；数字后有√则跳过，栏目数字/数字等于1则跳过。只有本次全新原生帧内唯一合法+5待完成卡，才允许一次点击。本轮已消费A/B同族、Quote、Puzzle、签到禁止自动重复。\n"
        +"只打开该小项。原生点击命令成功确认后，以单调时钟等待至少5秒，原Bing task仍合法时最多一次原生Back，再用新帧核验。这是调度策略，不是完成证明；IPC或调度延迟可能晚于5秒，仍受原180秒限制且不续期。5秒或Back exit0都不代表完成。不得答题、填写、登录、授权、付费、安装或分享；若仍未完成，停止并报告方法，不追加输入。\n"
        +"每阶段最多3张独立Root整屏PNG，每帧1次Maa OCR，总计最多6帧。仅同来源、双像素审计相等、全部清理确认、中部恒色且无More/无卡的明确健康未呈现状态可继续；其余HOLD、冲突、失败或未知立即停止。\n"
        +"每帧严格来源前置最多3组、后置1组；导航独立最多12组。无client/SF或UI probe。中间不恢复自身页面。未知资源强制保留隔离，不重试。\n"
        +"读取整屏，可能包含通知或其它可见敏感信息；不录音、不保存或外发原图/OCR。Root/native IPC异常无法保证硬中止。仅报告新帧可见状态，不宣称实名或点击因果。";
    static void uniquePendingChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){LinearLayout c=page(a,UNIQUE_PENDING_EXPLANATION+"\n"+status);button(a,c,"批准唯一+5待办：至多6帧、1次点击、成功确认至少5秒后至多1次返回",enabled,start);button(a,c,"停止并关闭",true,cancel);}
    static final String STATUS_RECOVERY_EXPLANATION="Root 任务卡原生验证 v36：独立恢复与当前逐卡只读\n"
        +"2026-11-12 03:35 至 05:00 UTC。一次新的可见批准，零任务点击；A/B、签到、Quote、puzzle均永不重触。这不是v31/v35续跑，也不补写旧业务闭环或因果。\n"
        +"仅隐藏自身露出现有Bing task15，不启动新的Template或Browser。新鲜严格Template证据直接只读，零Back；新鲜Browser及同task隐藏Template可靠链才至多一次原生Back，返回同一Template后只读。其它来源或UNKNOWN立即停止，TOP缺失/多值不轮询。\n"
        +"原会话180秒；只读阶段90秒不续期。最多3张独立Root整屏PNG，每帧1次Maa OCR。仅两层像素审计相等、同来源且全部清理，并有中部恒色无More/无卡的健康未呈现证据时才下一帧；其它HOLD/冲突/异常停止。\n"
        +"初始与返回共最多7组固定元数据；每帧严格前置最多3组、后置1组。无client/SF或UI probe，不以固定等待证明ready，中间和结束不自动恢复自身。\n"
        +"只用独立逐卡只读结果：未知卡为UNMODELED，完整卡族和业务资格不成立；空正文匿名完成卡不命名A/B。没有任务Run、TapActor、业务pass或旧continuation。\n"
        +"读取整屏可能包含通知或其它可见敏感信息；不录音、不保存或外发原图/OCR。Root/native IPC异常无法保证硬中止，资源未知保留隔离。中部为x[0,W)、y[floor(H/4),floor(3H/4))，flatCount为带内最频繁灰度像素数，不输出该灰度或摘要。";
    static void statusRecoveryChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){LinearLayout c=page(a,STATUS_RECOVERY_EXPLANATION+"\n"+status);button(a,c,"批准独立状态恢复：零任务点击、至多1次Back、3帧只读",enabled,start);button(a,c,"停止并关闭",true,cancel);}
    static final String SELECTED_PENDING_EXPLANATION="Root 任务卡原生验证 v35：独立B任务\n"
        +"2026-11-12 03:00 至 05:00 UTC。一次新的可见批准：仅新鲜原生证据中标题Do you know the answer?、正文Test your knowledge on these topics、未完成+5的B卡。A、签到、Quote、puzzle和其它卡都不点击。\n"
        +"最多1次任务点击、至多1次原生Back；不答题、不提交、不付款。点击后本次新鲜精确原Template已READY则不发Back直接AFTER；若精确Browser已READY才允许一次Back；UNKNOWN立即停止，不以等待或exit0当返回。\n"
        +"总会话180秒，BEFORE/AFTER各90秒且不延期；每阶段最多3张独立Root整屏PNG，每张一次Maa OCR。只有双审计相等、全部清理、同来源和中部恒色无More/无卡的健康未呈现才下一张，其它HOLD/冲突/未知停止。有效候选在同原帧采用，不另拍后冒用。\n"
        +"来源每候选前最多3组、后1组严格元数据；导航总12组，不借用source额度。不调用client/SF或UI probe，中间不恢复自身。\n"
        +"AFTER若只剩两张已知共同标题、各自身5和可靠勾，只报告此有限集合当前已完成，不给空正文匿名卡命名A/B，不声称实名同一任务或由本次点击造成。\n"
        +"读取整屏可能含通知或其它可见敏感信息；不录音，不保存或外发原图/OCR。Root/native IPC异常无法保证硬中止；资源未知保留隔离。";
    static void selectedPendingChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){LinearLayout c=page(a,SELECTED_PENDING_EXPLANATION+"\n"+status);button(a,c,"批准仅B任务：最多6帧、1次点击、至多1次Back",enabled,start);button(a,c,"停止并关闭",true,cancel);}
    static final String CURRENT_READ_EXPLANATION="Root 任务卡原生验证 v34：独立当前页逐卡只读\n"
        +"2026-11-12 02:25 至 03:30 UTC。一次新可见批准，零Back、零任务点击；不是v31业务续跑，也不是v32返回成功。不会点击A、B、签到、Quote或puzzle。\n"
        +"仅当本次严格元数据确认同签名Bing task15的Template处于前台且可见、已绘制、无未知窗口时，才取得新的只读帧；Browser或UNKNOWN立即停止。不会构造返回actor或任务Run。\n"
        +"原会话180秒；取得新鲜Template锚后读取阶段90秒，不因再观察延期。最多3张独立Root整屏PNG，每帧1次Maa OCR。仅像素双审计相等、实际清理和同来源确认，且中部恒色无More/无卡的健康未呈现状态可继续；其它HOLD、冲突或异常停止。\n"
        +"初始锚最多3组固定严格元数据；每帧前置最多3组、后置1组。没有client/SF或UI probe，不以固定等待当ready。中间及结束不自动恢复自身页面。\n"
        +"读取整屏，可能包含通知或其它可见敏感信息；不录音、不保存或外发原图/OCR。Root/native IPC异常无法保证硬中止，未知清理保留隔离。只报告独立验证的逐卡只读证据；未知中间卡为UNMODELED，完整卡族和业务资格不成立。空正文的匿名完成卡不命名为A。当前状态不证明由任何旧点击造成。\n"
        +"中部矩形x[0,W)、y[floor(H/4),floor(3H/4))；flatCount是该带最频繁灰度的像素数，不输出该灰度或摘要值。";
    static void progressLocateChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"上方内容定位 v42\n独立授权至2026-11-12 09:00 UTC，最多3张全屏/2次固定手指向下滑动。仅原Bing Template task15，零任务点击/Back/文字输入。滑动只是有界导航，不证明页面可滚动或已经移动。\n每帧重新核对来源、时效、原生行结构及清理。健康未呈现仅重读不滑动；确切奖励标题、白名单同一行格式、历史格式或精确假设候选即停。普通搜索/阅读词及裸分数不给任务身份；登录/错误、未知来源或清理立即停。\n布局候选最多24条，元数据只在最终资格检查通过时发布，原图和OCR原文不保存外发，不拼行或推断计数。诊断投影不授予业务动作权，实际导航次数如实报告。旧已消费任务不重开。\n"+status);
        button(a,c,"批准上方内容定位：最多2次向下滑动",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void entryDiagnosticChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"首页入口结构诊断 v47\n独立窗口2026-11-12 10:25–12:00 UTC，11:57 UTC停止接纳。仅1次设备内完整树读取；0点击/COMMIT/导航/Back/刷新/业务计数读取/截图/OCR，不更改ledger或重开旧任务。\n重新确认当前实际Bing Main来源，不使用旧task15。统计固定入口ID、精确Rewards的text/description及可见/启用/可点击条件；最多16条安全节点枚举/几何，完整树统计不受输出截断影响。实际父节点关系仅作诊断，不授予点击祖先或其它动作资格。\n原文、任意资源ID、hash和图像不保存外发；来源/取消/期限/完整性或清理异常停止，最终资格未通过不发布节点元数据，不重试。\n"+status);
        button(a,c,"批准V47：只读入口诊断，零导航",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void uiBoundPointsChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"结构绑定的明确积分只读 v53\n独立窗口2026-11-12 11:50–14:45 UTC，14:42 UTC停止接纳。仅本次真实Bing Template页面，零定位/导航/点击/滚动/Back/刷新/文字输入/截图/OCR，不重开旧预算或完成卡。\n一次idle后只读完整树。必须存在唯一已知真实任务层级，且对应可见卡片内的单字段具有固定中文任务标题、有限非数字说明和完整明确积分n/d后缀。说明不承担计数意义，不输出原文，不猜裸分式归属/目标，不把组件计数替代总计。重复、错卡、隐藏、非法数字或来源/清理异常即HOLD。\n结果来源明确标为真实结构绑定和显式积分后缀，不称固定历史说明匹配；不赋动作资格，不更改业务ledger。保留临时UiAutomation现有配置，不安装服务或更改权限/设置。\n"+status);
        button(a,c,"批准V53：只读结构绑定积分，零输入",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void uiLocateChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"原生任务区域定位与只读 v52\n独立窗口2026-11-12 11:30–14:30 UTC，14:27 UTC停止接纳。仅本次真实Bing Template页面，最多一次ACTION_SHOW_ON_SCREEN非点击定位，再一次idle与字段诊断；零积分任务点击/搜索/阅读/Back/刷新/文字输入/截图/OCR，不重开旧预算或完成卡。\n完整新树必须唯一匹配已知HOME→PANEL→ACTIVITIES→search/read真实层级，仅read容器明确提供SHOW_ON_SCREEN时可提交；提交前再次完整树和来源/节点身份复核。没有广告action即停止，无坐标或滚动fallback。一次提交结果未知不重发。成功且清理确认后等待至少5秒再查来源并读新树，不声称已可见或完成。\n诊断优先实际任务子树，分式仍未归属，不发布业务KNOWN。原文不保存外发，不改权限/服务/设置。提交前观察到取消会阻止提交；提交后只能尽力终止，可能已有定位，但不会继续读取或重试。\n"+status);
        button(a,c,"批准V52：最多一次区域定位，零任务点击",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void uiFieldChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"设备内UI字段与父链诊断 v51\n独立窗口2026-11-12 11:15–14:00 UTC，13:57 UTC停止接纳。仅本次真实Bing Template页面，零导航/点击/滚动/Back/刷新/文字输入/截图/OCR，不更改业务ledger或完成卡。\n一次最多3秒空闲等待后只读一棵完整树；空闲不证明加载完成。统计已知语义和字段分隔形态，区分可见/不可见语法匹配；最多24个候选及真实有限父链节点，仅输出白名单类别、位置、几何和有界统计。严格纯分式数字标UNATTRIBUTED，不猜归属，不发布业务KNOWN或动作资格。全树统计不受输出截断影响。\n原文、任意ID、字段片段和hash均不输出。不安装服务、不改权限/设置。来源、期限或清理异常即停止，不重复读取。\n"+status);
        button(a,c,"批准V51：只读字段与父链诊断，零导航",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void uiFormatChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"设备内UI计数只读 v50\n独立窗口2026-11-12 11:00–13:30 UTC，13:27 UTC停止接纳。仅允许本次真实Bing Template页面；零导航/点击/滑动/Back/刷新/文字输入/截图/OCR，不更改业务ledger或已消费任务。\n临时UiAutomation连接后，一次最多3秒等待500毫秒无事件，再读一次完整树。空闲不等于加载完成。仅当完整单字段命中已知历史语法才报告计数，包括固定Search/Read额外说明文案；不拼接节点、猜数字归属或推断目标。保留有界就绪诊断；诊断本身不是计数或动作资格。\n不输出原文、任意资源ID、字段片段或hash。不安装服务、不改设置/权限。来源、期限或清理异常即停，不重复读取。\n"+status);
        button(a,c,"批准V50：只读计数与字段诊断，零导航",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void uiReadyChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"设备内UI结构就绪观察 v49\n独立窗口2026-11-12 10:50–13:00 UTC，12:57 UTC停止接纳。仅允许本次真实Bing Template页面；不会再次导航。0点击/滑动/Back/刷新/文字输入/截图/OCR，不更改业务ledger或已消费任务。\n临时UiAutomation连接后，最多等待3秒观察500毫秒无事件，再读取一次完整树。安静不等于页面已加载；超时或来源/权限/清理异常即停止，不重复读取。仅报告固定就绪诊断和严格完整计数字段；未知不推断目标。\n保留现有无障碍服务，不安装服务、不改系统设置、不申请新权限。原文不保存或外发。\n"+status);
        button(a,c,"批准V49：只读页面就绪与计数，零导航",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void rewardsParentEntryChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"首页Rewards入口恢复 v48\n独立窗口2026-11-12 10:40–12:30 UTC，12:27 UTC停止接纳。最多1次首页Rewards入口导航点击，0积分任务点击；不Back、不刷新、不搜索输入、不截图/OCR，不更改业务ledger或重开已消费任务。\n只使用本次真实Bing Main或Template来源；若已是Template直接只读。首页必须完整新鲜树中唯一精确Rewards语义子节点，及真实直接父同边界可点击容器；两次核对同一对子父节点，仅点击该直接父容器，同task进入新Template后才读取。收到确认的导航结果且全部清理完成后至少等待5秒，再重新验证；不是页面已就绪的证明。\n固定设备内helper临时连接UiAutomation，保留现有无障碍服务；仅自身临时窗口元数据和view ID配置，不安装服务、不改系统设置、不申请新权限。原文不保存外发；提交决策前观察到取消会阻止提交；提交尝试后只能尽力终止，导航结果可能未知，但不再读取计数或重试。来源、期限或清理异常即停。\n"+status);
        button(a,c,"批准V48：首页入口最多1次，0积分任务点击",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void rewardsEntryChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"首页Rewards入口恢复 v46\n独立窗口2026-11-12 09:45–11:45 UTC，11:42 UTC停止接纳。最多1次首页Rewards入口导航点击，0积分任务点击；不Back、不刷新、不搜索输入、不截图/OCR，不更改业务ledger或重开已消费任务。\n只使用本次真实Bing Main或Template来源；若已是Template直接只读。首页必须精确唯一的原生Rewards入口，同task进入新Template后才读取。收到确认的导航结果且全部清理完成后至少等待5秒，再重新验证；不是页面已就绪的证明。\n固定设备内helper临时连接UiAutomation，保留现有无障碍服务；仅自身临时窗口元数据和view ID配置，不安装服务、不改系统设置、不申请新权限。原文不保存外发；提交决策前观察到取消会阻止提交；提交尝试后只能尽力终止，导航结果可能未知，但不再读取计数或重试。来源、期限或清理异常即停。\n"+status);
        button(a,c,"批准V46：首页入口最多1次，0积分任务点击",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void uiStructureChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"设备内UI结构只读 v45\n本次独立授权窗口2026-11-12 09:00–10:00 UTC，09:57 UTC停止接纳，只读取一次原Bing Template task15界面结构；不截图、不OCR、不点击/滑动/Back/输入，不更改业务ledger。\n固定本APK内存helper，保留现有无障碍服务；不安装/启用服务、不改设置、不申请新权限。正常短暂UiAutomation注册及断开恢复属于读取生命周期；拒绝或异常立即停，不更换flags或绕过限制。\n界面原文只在设备内存解析，不写XML/文件/日志；stdout只含固定枚举、数字、nonce及释放状态。仅严格完整字段显式当前/目标可报告UI_STRUCTURE结果，不猜固定30/60。前后来源/显示/清理及最终发布资格全部通过才展示，不声称与图像原子同帧。\n"+status);
        button(a,c,"批准V45设备内UI结构只读：零输入",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void structureReadChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"原生结构诊断 v43\n独立授权至2026-11-12 09:00 UTC，仅1张新全屏/1次OCR；零滑动/任务点击/Back/文字输入，无输入actor或后续捕获。\n重新核对原Bing Template task15，使用同一帧完整原生灰度图独立检测亮色区域，不用旧坐标裁图。最多8区域的有界像素特征、原始行索引包含/跨界和冲突计数，仅在最终资格检查通过时发布。\n区域只是像素候选，不证明闭合卡片或标题/分数归属。Reading/Search仍HOLD，低置信行不静默排除；原图/OCR原文/hash不保存外发。既有已消费任务不重开。\n"+status);
        button(a,c,"批准单帧原生结构诊断：零输入",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void checkpointReadChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"原生布局诊断 v41\n本次独立授权至2026-11-12 09:00 UTC，只有1张全屏、1次OCR；零点击、零滑动、零Back、零文字输入，不创建输入actor或后续捕获。\n必须重新建立同签名Bing task15严格Template来源；不能用旧观察替代。原图和OCR原文不保存、不外发。最多24条候选只报白名单枚举、原生行索引/坐标/置信度、有界裸分数；没有文字或文本hash。\n来源词表和HYPOTHESIS_ONLY候选分开。裸分数UNATTRIBUTED，不做跨行归属、不推断业务计数或动作权限。原有已消费任务仍排除。\n"+status);
        button(a,c,"批准单帧原生布局诊断：零输入",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void progressScanChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"搜索/阅读页内定位 v40\n本次独立授权至2026-11-12 09:00 UTC。最多3张全屏、2次固定向上滑动；只有原Bing Template task15身份和清理完整时才导航。零任务点击、零Back、零文字输入。\n中间区域固定滑动只是有界导航，不证明可滚动或发生移动。健康未呈现只重读、不滑动。任何准确任务标题/分数或高可信内容带搜索/阅读词即停止；其它不确定投递、清理、身份或期限问题立即停止。计数器仍严格解析，不拼接分行，不猜测积分。\n会话180秒、读取90秒不续期；整屏可能含敏感信息。原图/OCR不保存或外发，仅输出安全计数和诊断。旧任务/旧回执不重开。\n"+status);
        button(a,c,"批准有界页内定位：最多2次滑动",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void progressReadChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout c=page(a,"搜索/阅读原生进度只读 v39\n2026-11-12 05:00–09:00 UTC。只读当前Bing task15的严格Template页面。零任务点击、零Back、零滑动、零文字输入。\n全屏可能含敏感信息；原图/OCR不保存或外发。只报告搜索/阅读的已知或未知、分数和单位、完成或剩余。分行标题和分数不拼接；低可信或歧义保持未知。\n最多3帧，仅健康未呈现可继续；其余未知立即停止。180秒会话、90秒读取阶段不续期。所有已消费任务保持禁止重复。\n"+status);
        button(a,c,"批准搜索/阅读进度只读",enabled,start);button(a,c,"停止并关闭",true,cancel);
    }
    static void currentReadChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){LinearLayout c=page(a,CURRENT_READ_EXPLANATION+"\n"+status);button(a,c,"批准独立当前页只读：最多3帧，零Back、零任务点击",enabled,start);button(a,c,"停止并关闭",true,cancel);}
    static final String RETURN_READ_EXPLANATION="Root 任务卡原生验证 v32：独立返回与只读核验\n"
        +"2026-11-12 01:40 至 03:00 UTC。一次新的可见批准；这不是v31续跑，不复用旧Run、旧任务点击或旧返回许可。零任务点击，最多一次原生Back。\n"
        +"只对新鲜、同签名Bing task15的Browser与栈内精确Template证据授权返回；未知来源、窗口或资源立即停止。确认返回同一个Template后，最多3张独立Root整屏PNG，每帧1次Maa OCR。\n"
        +"原会话180秒；返回后只读阶段90秒不续期。仅像素双审计相等、全部清理确认、同来源且中部恒色无More/无卡的健康未呈现状态才继续下一帧，其它HOLD或冲突停止。没有任务actor、业务pass或旧业务continuation。\n"
        +"导航最多7组固定元数据；每帧前置最多3组、后置1组。不运行client/SF或UI probe，不以固定等待当ready。中间及结束不自动恢复自身页面。\n"
        +"读取整屏，可能包含通知或其他可见敏感信息；不录音、不保存或外发原图/OCR。Root/native IPC异常无法保证硬中止，未知保留隔离。只读状态不证明任务由旧点击造成。\n"
        +"中部为x[0,W)、y[floor(H/4),floor(3H/4))；flatCount是该带最频繁灰度像素数，不输出该灰度或图像摘要值。";
    static void returnReadChoice(Activity a,String status,boolean enabled,Runnable start,Runnable cancel){LinearLayout c=page(a,RETURN_READ_EXPLANATION+"\n"+status);button(a,c,"批准独立恢复：至多1次返回、3帧只读、零任务点击",enabled,start);button(a,c,"停止并关闭",true,cancel);}
    static java.util.List<String> returnReadDisplayChunks(java.util.List<String> reports){
        if(reports.size()>4)throw new IllegalArgumentException("recovery report count");java.util.List<String> out=new java.util.ArrayList<>();
        for(int i=0;i<reports.size();i++){String text=reports.get(i);if(text==null)continue;int count=Math.max(1,(text.length()+6499)/6500);
            for(int j=0;j<count;j++)out.add("returnReadReport="+(i+1)+"/"+reports.size()+" chunk="+(j+1)+"/"+count+"\n"+text.substring(j*6500,Math.min((j+1)*6500,text.length())));}
        return java.util.List.copyOf(out);
    }
    static void returnReadStatus(Activity a,java.util.List<String> reports,String status,Runnable cancel){LinearLayout c=page(a,status);for(String part:returnReadDisplayChunks(reports)){TextView v=new TextView(a);v.setText(part);v.setTextSize(14);v.setTextIsSelectable(true);c.addView(v);}button(a,c,"关闭",true,cancel);}
    static final String BOUNDED_EXPLANATION="Root 任务卡原生验证 v31：有界业务观察\n"
        +"2026-11-12 00:55 至 02:30 UTC。一次可见批准与一次隐藏自身。原会话180秒，BEFORE与AFTER各90秒，不因再观察延期。\n"
        +"每阶段至多3张全新独立Root整屏PNG，每帧1次Maa OCR，总计最多6帧。只有像素双审计相等、清理确认、同来源，且中部恒色、没有More标记和卡框的明确未呈现状态，才观察下一张；其它歧义、失败或未知立即停止。\n"
        +"仅标题 Do you know the answer?、正文 Challenge yourself with these trivia questions 的未完成+5卡可输入。有效原生帧直接交同帧核验，最多1次点击、1次原生Back，返回原页面后观察AFTER。不会再签到、触摸Quote或puzzle，不选正文Test your knowledge on these topics的兄弟卡，不答题、付款或提交。\n"
        +"每张严格来源前置最多3组与后置1组；业务导航独立12组。没有额外client/SF诊断或UI probe，没有固定等待当作页面证明。中间不恢复自身页面。\n"
        +"读取整屏，可能包含通知或其他可见敏感信息；不录音、不保存或外发原图/OCR。Root或native IPC异常无法保证硬中止，未知清理保留隔离。\n"
        +"中部统计矩形为x[0,W)、y[floor(H/4),floor(3H/4))；flatCount是最频繁灰度像素数，不输出该灰度或直方图。只显示安全标量，不显示图像摘要值。";
    static void boundedChoice(Activity activity,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout content=page(activity,BOUNDED_EXPLANATION+"\n"+status);
        button(activity,content,"批准本次任务：最多6帧、1次点击、至多1次返回",enabled,start);
        button(activity,content,"停止并关闭",true,cancel);
    }
    static java.util.List<String> boundedDisplayChunks(java.util.List<String> reports){
        if(reports.size()>RootBoundedBusinessScope.MAX_FRAMES)throw new IllegalArgumentException("bounded report count");
        java.util.List<String> chunks=new java.util.ArrayList<>();
        for(int n=0;n<reports.size();n++){String report=reports.get(n);if(report==null)continue;
            int count=Math.max(1,(report.length()+6499)/6500);
            for(int part=0;part<count;part++){int start=part*6500;
                chunks.add("boundedReport="+(n+1)+"/"+reports.size()+" chunk="+(part+1)+"/"+count+"\n"+report.substring(start,Math.min(start+6500,report.length())));}}
        return java.util.List.copyOf(chunks);
    }
    static void boundedStatus(Activity activity,java.util.List<String> reports,String status,Runnable cancel){
        LinearLayout content=page(activity,status);
        for(String chunk:boundedDisplayChunks(reports)){TextView label=new TextView(activity);label.setText(chunk);label.setTextSize(14);label.setTextIsSelectable(true);content.addView(label);}
        button(activity,content,"关闭",true,cancel);
    }
    static final String SERIES_EXPLANATION="Root 任务卡原生验证 v30：最多3帧只读序列\n"
        +"2026-11-12 00:30 至 01:30 UTC。一次可见批准、一次隐藏自身；总时限180秒，每帧最长90秒。最多3张独立Root整屏PNG，每帧1次Maa OCR。不是失败重试。\n"
        +"只有前一帧来源身份一致、PNG与Maa像素比较相等且所有资源实际清理后才观察下一帧。语义HOLD本身可继续；输送、像素、native或清理异常立即停。零任务输入、零返回，不产生页面候选、业务pass或旧业务continuation。\n"
        +"整屏可能包含通知或其他可见敏感信息；不录音、不保存或外发原图/OCR。Root或native IPC异常无法保证硬中止，未知保留隔离。\n"
        +"中部矩形为 x[0,W)、y[floor(H/4),floor(3H/4))；flatCount是该带最频繁灰度的像素数，不输出该灰度或直方图。跨帧比较只显示YES/NO/NOT_OBSERVED，不显示摘要值。\n"
        +"每帧严格前置最多3组、后置1组元数据；额外client/SF诊断及UI probe本轮均未调用，相关事实为UNOBSERVED。严格元数据也不是合成器像素身份或页面完成证明。中间及结束不自动恢复自身。";
    static void seriesChoice(Activity activity,String status,boolean enabled,Runnable start,Runnable cancel){
        LinearLayout content=page(activity,SERIES_EXPLANATION+"\n"+status);
        button(activity,content,"批准最多3帧只读序列，零输入",enabled,start);button(activity,content,"停止并关闭",true,cancel);
    }
    static void seriesStatus(Activity activity,java.util.List<String> reports,String status,Runnable cancel){
        if(reports.size()>3)throw new IllegalArgumentException("series report count");
        LinearLayout content=page(activity,status);int total=status.length();
        for(String report:reports){if(report==null||report.length()>7000||(total+=report.length())>22000)throw new IllegalArgumentException("series report budget");
            TextView label=new TextView(activity);label.setText(report);label.setTextSize(14);label.setTextIsSelectable(true);content.addView(label);}
        button(activity,content,"关闭",true,cancel);
    }
    static void choice(Activity activity,String status,boolean preflightEnabled,boolean businessEnabled,
                       Runnable preflight,Runnable business,Runnable cancel){
        if(RootFamilyDiagnosticProfile.ACTIVE){
            LinearLayout diagnostic=page(activity,"Root 任务卡原生验证 v28：同帧图像审计\n"+status
                +"\n\n2026-11-11 23:00 至 2026-11-12 00:30 UTC。单次最长90秒。只取得1张新的Root整屏PNG，做1次Maa原生OCR，不自动重拍。"
                +"\n\n同一PNG在本机比较标准Android解码与现有灰度解码，并核验Maa图像缓冲在设置后、同次任务静止后的像素一致性。只显示尺寸、通道、有限比较结果等审计标量，不把等待或元数据稳定当作页面像素身份。"
                +"\n\n整屏可能包含通知或其他可见敏感信息；不录音、不主动保存或外发原图/OCR。0任务输入、0返回，不构造actor，不产生业务pass或第二阶段。即使页面识别HOLD也只展示安全审计。"
                +"\n\n沿用每帧至多3次前置和1次后置元数据检查。所有审计临时缓冲也必须实际清理；未知清理保留隔离、不重试。Root或native IPC异常仍无法保证硬中止。");
            button(activity,diagnostic,"只读同帧图像审计：1帧，零输入",preflightEnabled,preflight);
            button(activity,diagnostic,"停止并关闭",true,cancel);return;
        }
        LinearLayout content=page(activity,"Root 任务卡原生验证 v27\n"+status+"\n\n"
            +"绝对窗口：2026-11-11 22:30–23:30 UTC。读取模拟器整屏，可能包含通知或其他可见敏感信息；不录音。已授权Root固定截图来源，app或IPC异常时无法保证硬中止，未知会停用本轮。\n\n"
            +"只读预检：取得1张全新原生帧，核验Bing来源、两张同名题卡与其各自正文及状态；不构造或执行任务输入。只有真实采集与原生资源清理确认、页面结果发布后，才开放独立业务批准。\n\n"
            +"业务批准：仅选择标题 Do you know the answer?、正文 Challenge yourself with these trivia questions 的未完成 +5 卡。不会选择正文 Test your knowledge on these topics 的同名卡，不再签到或触摸已完成Quote。\n"
            +"本次业务最多2张全新帧、1次任务点击、1次原生返回。只浏览，不代答、付款或提交；真实返回原页面后自动核验第二帧。每帧独立最多3次转场观察及1次来源复核，任务导航仍单独最多12组固定元数据读取。最长180秒，每帧最长90秒，到期不续期，失败不重试。\n"
            +"图像与OCR仅在本次内存中处理，正常结束后清理；未知清理会隔离本进程。");
        button(activity,content,"只读预检：1帧，零输入",preflightEnabled,preflight);
        button(activity,content,"独立批准本次业务：2帧、1次点击、至多1次返回",businessEnabled,business);
        button(activity,content,"停止并关闭",true,cancel);
    }
    static void status(Activity activity,String text,boolean terminal,Runnable cancel){
        LinearLayout content=page(activity,text);
        button(activity,content,terminal?"关闭":"停止",true,cancel);
    }
}
