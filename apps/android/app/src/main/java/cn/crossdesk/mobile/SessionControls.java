package cn.crossdesk.mobile;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.function.IntConsumer;
import static cn.crossdesk.mobile.MobileUi.*;

/** Safe-area overlay: a draggable 54 dp orb, an adaptive menu and a docked keyboard. */
@android.annotation.SuppressLint("ViewConstructor")
final class SessionControls extends FrameLayout {
    interface Actions {
        void disconnect(); void display(); void clipboard(); void key(int code);
        void chord(int code,int[] modifiers); void shortcut(int code); void text(String value); void mute(); void mouse();
        default boolean textEdit(int deletions,String value){return false;}
        void videoSettings(int field,int value);
        default void displayResolution(RemoteDisplayResolutions.Mode mode) { }
        void sendFile(); void saveFile(); void secureAttention();
        default void lockWorkstation() { }
        default void setRelativeMode(boolean relative) { }
        default void virtualMouseModeChanged(int mode) { }
        default void saveThumbMouseLayout(String json) { }
        default void moveCursorTo(float x, float y) { }
        default void toggleViewOnly() { }
        default void togglePrivacy() { }
        default void showDesktop() { }
        default void showAllWindows() { }
        default void unlockWithBiometric() { }
        default void configureUnlockPassword() { }
    }
    private final MobileUi ui;
    private final Actions actions;
    private final View orb;
    private final LinearLayout waiting;
    private View shield;
    private LinearLayout menu;
    private final RemoteKeyboard keyboard;
    private final IntConsumer keyboardInsetChanged;
    private VirtualMouseBar mouseBar;
    private ThumbSplitMouseOverlay thumbOverlay;
    private int mouseMode = VirtualMouseBar.MODE_EXPANDED;
    private String savedThumbLayout = "";
    private TextView statsHud;
    private int statsHudMode = 2;
    private long connectedAtElapsedMs;
    private boolean letterboxDocked;
    private int letterboxLeftPx, letterboxRightPx;
    private final Runnable durationTicker = new Runnable() {
        @Override public void run() {
            if (statsHud != null && statsHud.getVisibility() == VISIBLE && !pictureInPicture) {
                renderStatsHud();
            }
        }
    };
    private LinearLayout sideBar, sideBarItems;
    private MobileUi.Icon sideBarToggleIcon;
    private boolean sideBarExpanded = true;
    private float mouseX = Float.NaN, mouseY = Float.NaN;
    private boolean mouseVisible = true, pictureInPicture;
    private ScrollView menuScroll;
    private int menuColumns=3;
    private final ViewTreeObserver.OnGlobalLayoutListener legacyKeyboardLayout=this::legacyKeyboardLayout;
    private void legacyKeyboardLayout(){
        if(android.os.Build.VERSION.SDK_INT>=30)return;
        android.graphics.Rect visible=new android.graphics.Rect();getWindowVisibleDisplayFrame(visible);
        int height=Math.max(0,getRootView().getHeight()-visible.bottom);
        keyboardInsets(height,height>ui.dp(80),0);
    }
    private TextView videoFeedback,resolutionButton,resolutionFeedback;
    private RemoteDisplayResolutions resolutions=new RemoteDisplayResolutions();
    private int resolutionDisplay;
    private android.app.AlertDialog resolutionDialog;
    private RemotePrivacyScreen privacy=new RemotePrivacyScreen();
    private View privacyButton;
    private MobileUi.Icon privacyIcon;
    private TextView privacyFeedback,privacyCaption;
    private TextView transferLabel,saveFileButton;
    private String transferStatus="";
    private boolean hasReceivedFile;
    private String menuPage="controls";
    private RemoteNetworkStatistics.Snapshot network=new RemoteNetworkStatistics().snapshot(0);
    private RemoteVideoSettings settings=new RemoteVideoSettings(1);
    private final java.util.List<SettingRow> settingRows=new java.util.ArrayList<>();
    private TextView[][] trafficCells;
    private LinearLayout[] trafficRows;
    private static final int CONNECTION_MODE = 4, LOCAL_ENDPOINT = 5, REMOTE_ENDPOINT = 6, ENCRYPTION = 7;
    private TextView[] networkDetails;
    private boolean endpointsExpanded;
    private View encryptionLock,encryptionOpen;
    private boolean muted,relative=true,orbPositioned,secureAttentionVisible,viewOnly;
    SessionControls(Context context,MobileUi ui,IntConsumer keyboardInsetChanged,Actions actions){
        super(context);this.ui=ui;this.actions=actions;
        this.keyboardInsetChanged = keyboardInsetChanged;
        keyboard=new RemoteKeyboard(context,ui,actions,()->{requestLayout();positionMouse();});
        addView(keyboard,new LayoutParams(-1,ui.dp(44),Gravity.BOTTOM));
        // This container must remain transparent to remote touch outside its controls.
        waiting=ui.column();waiting.setMinimumWidth(ui.dp(200));waiting.setGravity(Gravity.CENTER);waiting.setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(20));waiting.setBackground(ui.background(0xB0202024,14));
        ProgressBar progress=new ProgressBar(context);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));waiting.addView(progress,ui.size(28,28));ui.gap(waiting,12);TextView message=ui.text(L10n.tr("等待远端画面…"),14,false);message.setTextColor(Color.WHITE);message.setGravity(Gravity.CENTER);waiting.addView(message);addView(waiting,new LayoutParams(-2,-2,Gravity.CENTER));
        orb=ui.iconButton("display",L10n.tr("展开远程控制菜单"),()->{if(menu==null)openMenu("controls");else closeMenu();});
        ImageView brand=new ImageView(context);brand.setImageResource(R.drawable.mobile_brand);brand.setScaleType(ImageView.ScaleType.CENTER_CROP);brand.setBackground(ui.background(Color.WHITE,23));brand.setClipToOutline(true);brand.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((FrameLayout)orb).removeAllViews();((FrameLayout)orb).addView(brand,new LayoutParams(ui.dp(46),ui.dp(46),Gravity.CENTER));
        orb.setBackground(ui.background(0xFFF5F5F7,27));orb.setElevation(ui.dp(7));addView(orb,new LayoutParams(ui.dp(54),ui.dp(54)));
        drag(orb,orb,()->{if(menu==null)openMenu("controls");else closeMenu();});
        initStatsHud(context);
        initSideBar(context);
    }
    private void initStatsHud(Context context){
        statsHud=new TextView(context);
        statsHud.setTextSize(10);
        statsHud.setTextColor(0xFFE5E5EA);
        statsHud.setTypeface(android.graphics.Typeface.MONOSPACE);
        statsHud.setFontFeatureSettings("tnum");
        statsHud.setPadding(ui.dp(8),ui.dp(5),ui.dp(8),ui.dp(5));
        android.graphics.drawable.GradientDrawable bg=ui.background(0xAA121216,8);
        bg.setStroke(ui.dp(.5f),0x33FFFFFF);
        statsHud.setBackground(bg);
        statsHud.setClickable(true);
        statsHud.setOnClickListener(v->{statsHudMode=(statsHudMode+1)%3;renderStatsHud();});
        LayoutParams p=new LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
        p.leftMargin=ui.dp(12);p.topMargin=ui.dp(12);
        addView(statsHud,p);
        statsHud.setVisibility(GONE);
    }
    private void initSideBar(Context context){
        sideBar=ui.column();
        sideBar.setGravity(Gravity.CENTER_HORIZONTAL);
        sideBar.setPadding(ui.dp(4),ui.dp(6),ui.dp(4),ui.dp(6));
        android.graphics.drawable.GradientDrawable bg=ui.background(0xCC16161B,14);
        bg.setStroke(ui.dp(.5f),0x33FFFFFF);
        sideBar.setBackground(bg);
        sideBar.setElevation(ui.dp(6));

        FrameLayout collapseBtn=new FrameLayout(context);
        collapseBtn.setClickable(true);collapseBtn.setFocusable(true);
        sideBarToggleIcon=ui.icon("chevron_right",0xFFAEAEB2);
        collapseBtn.addView(sideBarToggleIcon,new LayoutParams(ui.dp(18),ui.dp(18),Gravity.CENTER));
        collapseBtn.setOnClickListener(v->{sideBarExpanded=!sideBarExpanded;updateSideBarState();});
        sideBar.addView(collapseBtn,ui.size(44,24));

        sideBarItems=ui.column();
        sideBarItems.setGravity(Gravity.CENTER_HORIZONTAL);
        addSideButton(context,"sliders",L10n.tr("操作"),()->{if(menu==null)openMenu("controls");else closeMenu();},actions::mouse);
        addSideButton(context,"keyboard",L10n.tr("键盘"),()->{closeMenu();toggleKeyboard();},null);
        addSideButton(context,"mouse",L10n.tr("鼠标"),this::cycleVirtualMouseMode,this::toggleMouse);
        addSideButton(context,"display",L10n.tr("桌面"),actions::showDesktop,null);
        addSideButton(context,"windows",L10n.tr("窗口"),actions::showAllWindows,null);
        addSideButton(context,"unlock",L10n.tr("解锁"),actions::unlockWithBiometric,actions::configureUnlockPassword);
        sideBar.addView(sideBarItems,ui.size(-2,-2));

        LayoutParams p=new LayoutParams(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL);
        p.rightMargin=ui.dp(8);
        addView(sideBar,p);
        sideBar.setVisibility(GONE);
    }
    private void addSideButton(Context context,String iconName,String label,Runnable onClick,Runnable onLongClick){
        LinearLayout btn=ui.column();
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
        btn.setClickable(true);btn.setFocusable(true);
        btn.setBackground(ui.background(0x00000000,10));
        btn.addView(ui.icon(iconName,Color.WHITE),ui.size(18,18));
        ui.gap(btn,2);
        TextView text=ui.text(label,9,false);
        text.setTextColor(0xFFE5E5EA);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine();
        btn.addView(text);
        btn.setOnClickListener(v->onClick.run());
        if(onLongClick!=null)btn.setOnLongClickListener(v->{onLongClick.run();return true;});
        LinearLayout.LayoutParams lp=ui.size(44,-2);
        lp.topMargin=ui.dp(2);
        sideBarItems.addView(btn,lp);
    }
    private void updateSideBarState(){
        if(sideBarItems!=null)sideBarItems.setVisibility(sideBarExpanded?VISIBLE:GONE);
        if(sideBarToggleIcon!=null)sideBarToggleIcon.setName(sideBarExpanded?"chevron_right":"chevron_left");
    }
    void setConnectedTime(long elapsedMs){
        if(elapsedMs>0)connectedAtElapsedMs=elapsedMs;
        renderStatsHud();
    }
    void onViewportRectChanged(float left,float top,float width,float height,int containerWidth,int containerHeight,boolean zoomed){
        int leftGap=Math.max(0,Math.round(left));
        int rightGap=Math.max(0,Math.round(containerWidth-(left+width)));
        boolean nextDocked=!pictureInPicture&&!zoomed&&leftGap>=ui.dp(56)&&rightGap>=ui.dp(56);
        if(nextDocked==letterboxDocked&&leftGap==letterboxLeftPx&&rightGap==letterboxRightPx)return;
        boolean dockChanged=nextDocked!=letterboxDocked;
        letterboxDocked=nextDocked;
        letterboxLeftPx=leftGap;
        letterboxRightPx=rightGap;
        applyLetterboxDocking(dockChanged);
        renderStatsHud();
        if(mouseMode==VirtualMouseBar.MODE_THUMB_SPLIT)positionMouse();
    }
    private void applyLetterboxDocking(boolean dockChanged){
        if(statsHud!=null){
            LayoutParams hudLp=(LayoutParams)statsHud.getLayoutParams();
            int targetLeft=letterboxDocked?ui.dp(6):ui.dp(12);
            int targetTop=letterboxDocked?ui.dp(14):ui.dp(12);
            if(hudLp.leftMargin!=targetLeft||hudLp.topMargin!=targetTop){
                hudLp.leftMargin=targetLeft;hudLp.topMargin=targetTop;
                statsHud.setLayoutParams(hudLp);
            }
            if(letterboxDocked){
                if(dockChanged){
                    statsHud.setBackground(null);
                    statsHud.setPadding(ui.dp(2),ui.dp(2),ui.dp(2),ui.dp(2));
                    statsHud.setTextSize(9.5f);
                }
                statsHud.setMaxWidth(Math.max(ui.dp(44),letterboxLeftPx-ui.dp(8)));
            }else if(dockChanged){
                android.graphics.drawable.GradientDrawable bg=ui.background(0xAA121216,8);
                bg.setStroke(ui.dp(.5f),0x33FFFFFF);
                statsHud.setBackground(bg);
                statsHud.setPadding(ui.dp(8),ui.dp(5),ui.dp(8),ui.dp(5));
                statsHud.setTextSize(10f);
                statsHud.setMaxWidth(Integer.MAX_VALUE);
            }
        }
        if(sideBar!=null){
            LayoutParams sbLp=(LayoutParams)sideBar.getLayoutParams();
            int targetRight=letterboxDocked?Math.max(ui.dp(4),(letterboxRightPx-ui.dp(52))/2):ui.dp(8);
            if(sbLp.rightMargin!=targetRight){
                sbLp.rightMargin=targetRight;
                sideBar.setLayoutParams(sbLp);
            }
            if(dockChanged){
                if(letterboxDocked){
                    sideBar.setBackground(ui.background(0x66121216,14));
                }else{
                    android.graphics.drawable.GradientDrawable bg=ui.background(0xCC16161B,14);
                    bg.setStroke(ui.dp(.5f),0x33FFFFFF);
                    sideBar.setBackground(bg);
                }
            }
        }
    }
    private static String formatDuration(long sec){
        long h=sec/3600,m=(sec%3600)/60,s=sec%60;
        return String.format(java.util.Locale.US,"%02d:%02d:%02d",h,m,s);
    }
    private void renderStatsHud(){
        if(statsHud==null)return;
        statsHud.removeCallbacks(durationTicker);
        boolean show=!pictureInPicture&&waiting.getVisibility()==GONE&&statsHudMode>0;
        statsHud.setVisibility(show?VISIBLE:GONE);
        if(!show)return;
        long elapsedSec=connectedAtElapsedMs>0?Math.max(0L,(android.os.SystemClock.elapsedRealtime()-connectedAtElapsedMs)/1000L):0L;
        String duration=formatDuration(elapsedSec);
        if(letterboxDocked){
            if(statsHudMode==1){
                statsHud.setText(duration+"\n"+network.fps+" fps\n"+RemoteNetworkStatistics.latency(network.rtt));
            }else{
                RemoteNetworkStatistics.Traffic video=network.report==null?null:network.report.traffic[0];
                String in=RemoteNetworkStatistics.bitrate(video==null?-1:video.inbound);
                String loss=RemoteNetworkStatistics.loss(video==null?Double.NaN:video.loss);
                statsHud.setText(duration+"\n"+network.mode()+"\n"+network.fps+" fps\n"+in+"\n"
                        +RemoteNetworkStatistics.latency(network.rtt)+"\n"+loss+" loss\n"+network.resolution());
            }
        }else{
            if(statsHudMode==1){
                statsHud.setText(duration+" · "+network.fps+" FPS · "+RemoteNetworkStatistics.latency(network.rtt));
            }else{
                RemoteNetworkStatistics.Traffic video=network.report==null?null:network.report.traffic[0];
                String in=RemoteNetworkStatistics.bitrate(video==null?-1:video.inbound);
                String loss=RemoteNetworkStatistics.loss(video==null?Double.NaN:video.loss);
                statsHud.setText(duration+"  "+network.mode()+"  "+network.fps+" FPS  "+in+"\n"
                        +L10n.tr("延时 ")+RemoteNetworkStatistics.latency(network.rtt)
                        +"  "+L10n.tr("丢包 ")+loss+"  "+network.resolution());
            }
        }
        if(connectedAtElapsedMs>0)statsHud.postDelayed(durationTicker,1000);
    }
    void setRelative(boolean value){relative=value;if(menu!=null&&menuPage.equals("controls"))openMenu("controls");}
    void setViewOnly(boolean value){
        viewOnly=value;
        if(value){cancelMouseInput();mouseVisible=false;closeKeyboard();positionMouse();}
        if(menu!=null&&menuPage.equals("controls"))openMenu("controls");
    }
    void setHostPlatform(String platform){
        keyboard.setPlatform(platform);
        boolean visible="windows".equals(platform);
        if(secureAttentionVisible==visible)return;
        secureAttentionVisible=visible;
        if(menu!=null&&menuPage.equals("controls"))openMenu("controls");
    }
    void setVirtualMouseMode(int mode){
        int normalized=Math.max(VirtualMouseBar.MODE_EXPANDED,Math.min(VirtualMouseBar.MODE_MINI,mode));
        mouseMode=normalized;
        if(mouseBar!=null)mouseBar.setMode(normalized);
        positionMouse();
    }
    void setThumbMouseLayout(String json){
        savedThumbLayout=json==null?"":json;
        if(thumbOverlay!=null)thumbOverlay.loadLayout(savedThumbLayout);
    }
    void attachMouseBar(FrameLayout safeArea, int controlsIndex, VirtualMouseBar bar) {
        mouseBar=bar;
        thumbOverlay=new ThumbSplitMouseOverlay(ui,bar.mouseInput(),json->{
            savedThumbLayout=json;
            actions.saveThumbMouseLayout(json);
        });
        thumbOverlay.loadLayout(savedThumbLayout);
        thumbOverlay.setVisibility(GONE);
        // Keep thumb overlay and bar above the video for split touch, and below the orb and menu.
        safeArea.addView(thumbOverlay,controlsIndex,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        safeArea.addView(bar,controlsIndex+1,new FrameLayout.LayoutParams(ui.dp(bar.currentWidthDp()),ui.dp(bar.currentHeightDp()),Gravity.TOP|Gravity.LEFT));
        bar.mouseInput().didCancel=()->{
            bar.resetTouches();
            if(thumbOverlay!=null)thumbOverlay.resetTouches();
        };
        drag(bar.dragHandle,bar,null,null);
        drag(bar.miniOrb,bar,()->{
            if(mouseMode==VirtualMouseBar.MODE_THUMB_SPLIT&&thumbOverlay!=null&&thumbOverlay.isEditingLayout()){
                thumbOverlay.setEditingLayout(false);
                Toast.makeText(getContext(),L10n.tr("已锁定极简鼠标按键位置"),Toast.LENGTH_SHORT).show();
            }else{
                bar.setMode(VirtualMouseBar.MODE_EXPANDED);
            }
        },()->{
            if(mouseMode==VirtualMouseBar.MODE_THUMB_SPLIT&&thumbOverlay!=null){
                boolean next=!thumbOverlay.isEditingLayout();
                thumbOverlay.setEditingLayout(next);
                Toast.makeText(getContext(),L10n.tr(next?"拖动调整左右键和滚轮位置，点击迷你球锁定":"已锁定极简鼠标按键位置"),Toast.LENGTH_SHORT).show();
            }
        });
        bar.onLayoutModeChanged=()->{
            mouseMode=bar.mode();
            actions.virtualMouseModeChanged(mouseMode);
            if(mouseMode!=VirtualMouseBar.MODE_THUMB_SPLIT&&thumbOverlay!=null&&thumbOverlay.isEditingLayout()){
                thumbOverlay.setEditingLayout(false);
            }
            ViewGroup.LayoutParams lp=bar.getLayoutParams();
            lp.width=ui.dp(bar.currentWidthDp());
            lp.height=ui.dp(bar.currentHeightDp());
            bar.setLayoutParams(lp);
            positionMouse();
            if(menu!=null&&menuPage.equals("controls"))openMenu("controls");
        };
        bar.setMode(mouseMode);
        bar.setVisibility(GONE);
    }
    void cancelMouseInput(){
        if(mouseBar!=null)mouseBar.cancelInput();
        if(thumbOverlay!=null)thumbOverlay.resetTouches();
    }
    void setPictureInPicture(boolean value){
        pictureInPicture=value;
        if(value){cancelMouseInput();closeMenu();closeKeyboard();}
        setVisibility(value?GONE:VISIBLE);positionMouse();renderStatsHud();updateSideBarVisibility();
    }
    private void updateSideBarVisibility(){
        if(sideBar==null)return;
        boolean show=!pictureInPicture&&waiting.getVisibility()==GONE;
        sideBar.setVisibility(show?VISIBLE:GONE);
    }
    private void toggleMouse(){
        if(viewOnly){actions.toggleViewOnly();return;}
        cancelMouseInput();mouseVisible=!mouseVisible;
        if(mouseVisible&&mouseBar!=null&&mouseBar.isCollapsed())mouseBar.setMode(VirtualMouseBar.MODE_EXPANDED);
        closeMenu();positionMouse();
    }
    private void cycleVirtualMouseMode(){
        if(viewOnly){actions.toggleViewOnly();return;}
        if(!mouseVisible){
            mouseVisible=true;
            if(mouseBar!=null)mouseBar.setMode(mouseMode);
            positionMouse();
            return;
        }
        int next=(mouseMode+1)%3;
        if(mouseBar!=null)mouseBar.setMode(next);
    }
    private void selectVirtualMouseMode(int mode){
        if(viewOnly){actions.toggleViewOnly();return;}
        mouseVisible=true;
        if(mouseBar!=null&&mouseBar.mode()!=mode)mouseBar.setMode(mode);
        else{mouseMode=mode;actions.virtualMouseModeChanged(mode);positionMouse();if(menu!=null&&menuPage.equals("controls"))openMenu("controls");}
    }
    private void positionMouse(){
        if(mouseBar==null)return;
        boolean visible=!pictureInPicture&&!viewOnly&&mouseVisible&&waiting.getVisibility()==GONE;
        if(!visible){
            if(mouseBar.getVisibility()==VISIBLE||(thumbOverlay!=null&&thumbOverlay.getVisibility()==VISIBLE))cancelMouseInput();
            if(thumbOverlay!=null){
                if(thumbOverlay.isEditingLayout())thumbOverlay.setEditingLayout(false);
                thumbOverlay.setVisibility(GONE);
            }
            mouseBar.setVisibility(GONE);
            return;
        }
        if(thumbOverlay!=null){
            if(mouseMode==VirtualMouseBar.MODE_THUMB_SPLIT){
                int keyboardBottom=keyboard.isOpen()&&keyboard.getHeight()>0?Math.max(0,getHeight()-keyboard.getTop()):0;
                int rightAvoid=(sideBar!=null&&sideBar.getVisibility()==VISIBLE)?Math.max(ui.dp(56),sideBar.getWidth()+ui.dp(12)):ui.dp(56);
                if(letterboxDocked)rightAvoid=Math.max(rightAvoid,letterboxRightPx);
                thumbOverlay.updateBounds(getWidth(),getHeight(),keyboardBottom,rightAvoid);
                thumbOverlay.setVisibility(VISIBLE);
            }else{
                if(thumbOverlay.isEditingLayout())thumbOverlay.setEditingLayout(false);
                thumbOverlay.resetTouches();
                thumbOverlay.setVisibility(GONE);
            }
        }
        float width=ui.dp(mouseBar.currentWidthDp()),height=ui.dp(mouseBar.currentHeightDp()),gap=ui.dp(6),margin=ui.dp(8);
        float x = Float.isNaN(mouseX) ? getWidth() - width - ui.dp(16) : mouseX;
        float y = Float.isNaN(mouseY) ? getHeight() - height - ui.dp(24) : mouseY;
        float maxY = getHeight() - height - margin;
        if (keyboard.isOpen() && keyboard.getHeight() > 0)
            maxY = Math.min(maxY, keyboard.getY() - height - gap);
        mouseBar.setTranslationX(bound(x,margin,getWidth()-width-margin));
        mouseBar.setTranslationY(bound(y,margin,maxY));
        mouseBar.setVisibility(VISIBLE);
    }
    void setMuted(boolean value){muted=value;if(menu!=null&&menuPage.equals("controls"))openMenu("controls");}
    void videoSize(int width,int height){waiting.setVisibility(GONE);if(connectedAtElapsedMs==0)connectedAtElapsedMs=android.os.SystemClock.elapsedRealtime();positionMouse();renderStatsHud();updateSideBarVisibility();}
    void resetVideoStatistics(){statistics(new RemoteNetworkStatistics.Snapshot(network.report,0,0,0,Double.NaN,network.rtt,network.remoteEncoder,network.localDecoder));}
    void statistics(RemoteNetworkStatistics.Snapshot value){network=value;renderNetwork();renderStatsHud();}
    void videoSettings(RemoteVideoSettings value){settings=value;renderVideoSettings();}
    void displayResolutions(RemoteDisplayResolutions value,int display){resolutions=value;resolutionDisplay=display;renderResolutions();}
    void privacyScreen(RemotePrivacyScreen value){privacy=value;renderPrivacy();}
    void fileStatus(String status,boolean received){transferStatus=status;hasReceivedFile=received;renderFileStatus();}
    private void renderFileStatus(){
        if(transferLabel!=null){transferLabel.setText(transferStatus);transferLabel.setVisibility(transferStatus.isEmpty()?GONE:VISIBLE);}
        if(saveFileButton!=null)saveFileButton.setVisibility(hasReceivedFile?VISIBLE:GONE);
    }
    private void closeMenu(){if(resolutionDialog!=null){resolutionDialog.dismiss();resolutionDialog=null;}resolutionButton=resolutionFeedback=null;if(menu!=null){removeView(menu);menu=null;menuScroll=null;}if(shield!=null){removeView(shield);shield=null;}videoFeedback=transferLabel=saveFileButton=null;privacyFeedback=privacyCaption=null;privacyButton=null;privacyIcon=null;trafficCells=null;trafficRows=null;networkDetails=null;encryptionLock=encryptionOpen=null;settingRows.clear();orb.setContentDescription(L10n.tr("展开远程控制菜单"));}
    private void openMenu(String page){
        cancelMouseInput();
        closeMenu();menuPage=page;
        shield=new View(getContext()) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                // Let touches starting on the exposed bar reach its own buttons.
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mouseBar != null && mouseBar.isShown()) {
                    float x = event.getX() - mouseBar.getTranslationX();
                    float y = event.getY() - mouseBar.getTranslationY();
                    if (x >= 0 && y >= 0 && x < mouseBar.getWidth() && y < mouseBar.getHeight()) return false;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        shield.setOnClickListener(v->closeMenu());addView(shield,new LayoutParams(-1,-1));
        menu=ui.column();menu.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));ui.card(menu,18);menu.setElevation(ui.dp(12));
        menu.setClickable(true);
        menu.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout header=ui.row();
        if(!page.equals("controls"))header.addView(ui.iconButton("back",L10n.tr("返回控制栏"),()->openMenu("controls")),ui.size(28,32));
        String titleText=page.equals("controls")?L10n.tr("●  已连接")+" · b"+buildCode():page.equals("video")?L10n.tr("画面设置"):L10n.tr("网络状态");
        TextView title=ui.text(titleText,14,true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(ui.iconButton("close",L10n.tr("关闭控制栏"),this::closeMenu),ui.size(30,32));menu.addView(header);ui.gap(menu,9);
        if(page.equals("video")){videoFeedback=ui.text("",12,false);videoFeedback.setTextColor(SECONDARY);videoFeedback.setPadding(0,0,0,ui.dp(9));menu.addView(videoFeedback);}
        ScrollView scroll=new ScrollView(getContext());menuScroll=scroll;menu.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=ui.column();scroll.addView(body);
        if(page.equals("network")){
            // Reserve the scrollbar's width plus a gap before right-aligned statistics.
            scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_INSET);body.setPadding(0,0,ui.dp(6),0);
        }
        if(page.equals("controls")){
            String[] labels={L10n.tr("键盘"),mouseVisible?L10n.tr("收起鼠标"):L10n.tr("虚拟鼠标"),viewOnly?L10n.tr("退出仅观看"):L10n.tr("仅观看"),L10n.tr("显示器"),L10n.tr("画面设置"),muted?L10n.tr("静音"):L10n.tr("声音"),L10n.tr("发送文件"),L10n.tr("网络状态"),"Ctrl+Alt+Del",L10n.tr("剪贴板"),L10n.tr("隐私屏"),L10n.tr("锁定电脑")};
            String[] icons={"keyboard","mouse","eye","display","sliders","audio","folder","chart","lock","clipboard","eye","lock"};
            Runnable[] clicks={()->{closeMenu();toggleKeyboard();},this::toggleMouse,actions::toggleViewOnly,actions::display,()->openMenu("video"),actions::mute,actions::sendFile,()->openMenu("network"),actions::secureAttention,actions::clipboard,actions::togglePrivacy,()->{closeMenu();actions.lockWorkstation();}};
            menuColumns=controlColumns();
            java.util.List<Integer> visibleControls=new java.util.ArrayList<>();
            for(int index=0;index<labels.length;index++){
                if(index==8&&!secureAttentionVisible||index==10&&!privacy.desktopAvailable)continue;
                visibleControls.add(index);
            }
            int controlCount=visibleControls.size();
            for(int row=0;row<(controlCount+menuColumns-1)/menuColumns;row++){LinearLayout line=ui.row();for(int col=0;col<menuColumns;col++){int position=row*menuColumns+col;if(position>=controlCount){View spacer=new View(getContext());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ui.dp(52),1);if(col>0)p.leftMargin=ui.dp(7);line.addView(spacer,p);continue;}int index=visibleControls.get(position);LinearLayout control=ui.column();control.setGravity(Gravity.CENTER);boolean active=(index==2&&viewOnly)||(index==1&&mouseVisible);control.setBackground(ui.background(active?BLUE:SURFACE_ELEVATED,10));MobileUi.Icon icon=ui.icon(icons[index],active?Color.WHITE:INK);control.addView(icon,ui.size(19,19));ui.gap(control,4);TextView caption=ui.text(labels[index],10,false);caption.setTextColor(active?Color.WHITE:INK);caption.setMaxLines(2);caption.setAutoSizeTextTypeUniformWithConfiguration(8,10,1,android.util.TypedValue.COMPLEX_UNIT_SP);caption.setGravity(Gravity.CENTER);control.addView(caption);control.setContentDescription(labels[index]);control.setClickable(true);control.setFocusable(true);control.setOnClickListener(v->clicks[index].run());
                    if(index==10){privacyButton=control;privacyIcon=icon;privacyCaption=caption;}
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ui.dp(52),1);if(col>0)p.leftMargin=ui.dp(7);line.addView(control,p);
                }body.addView(line);if((row+1)*menuColumns<controlCount)ui.gap(body,7);}
            ui.gap(body,8);
            LinearLayout modeBar=ui.row();
            android.graphics.drawable.GradientDrawable modeShell=ui.background(MobileUi.BACKGROUND,10);
            modeShell.setStroke(ui.dp(.5f),LINE);
            modeBar.setBackground(modeShell);
            modeBar.setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));
            TextView touchBtn=ui.action(L10n.tr("屏幕触控"),!relative?Color.WHITE:INK,()->actions.setRelativeMode(false));
            touchBtn.setTextSize(13);touchBtn.setMinHeight(ui.dp(34));
            if(!relative)touchBtn.setBackground(ui.background(BLUE,8));
            TextView cursorBtn=ui.action(L10n.tr("鼠标指针"),relative?Color.WHITE:INK,()->actions.setRelativeMode(true));
            cursorBtn.setTextSize(13);cursorBtn.setMinHeight(ui.dp(34));
            if(relative)cursorBtn.setBackground(ui.background(BLUE,8));
            modeBar.addView(touchBtn,new LinearLayout.LayoutParams(0,ui.dp(34),1));
            modeBar.addView(cursorBtn,new LinearLayout.LayoutParams(0,ui.dp(34),1));
            body.addView(modeBar,ui.size(-1,40));
            ui.gap(body,6);
            LinearLayout mouseModeBar=ui.row();
            android.graphics.drawable.GradientDrawable mouseModeShell=ui.background(MobileUi.BACKGROUND,10);
            mouseModeShell.setStroke(ui.dp(.5f),LINE);
            mouseModeBar.setBackground(mouseModeShell);
            mouseModeBar.setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));
            String[] mouseModeLabels={L10n.tr("展开面板"),L10n.tr("双拇指极简"),L10n.tr("迷你球")};
            for(int i=0;i<mouseModeLabels.length;i++){
                final int modeIndex=i;
                boolean selected=mouseVisible&&mouseMode==modeIndex;
                TextView btn=ui.action(mouseModeLabels[i],selected?Color.WHITE:INK,()->selectVirtualMouseMode(modeIndex));
                btn.setTextSize(12);btn.setMinHeight(ui.dp(28));btn.setSingleLine();
                btn.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
                if(selected)btn.setBackground(ui.background(BLUE,8));
                mouseModeBar.addView(btn,new LinearLayout.LayoutParams(0,ui.dp(28),1));
            }
            body.addView(mouseModeBar,ui.size(-1,34));
            privacyFeedback=ui.text("",11,false);privacyFeedback.setTextColor(SECONDARY);privacyFeedback.setPadding(0,ui.dp(7),0,0);privacyFeedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(privacyFeedback);renderPrivacy();
            ui.gap(body,7);transferLabel=ui.text(transferStatus,11,false);transferLabel.setTextColor(SECONDARY);transferLabel.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(transferLabel);
            saveFileButton=ui.action(L10n.tr("保存收到的文件"),BLUE,actions::saveFile);body.addView(saveFileButton);renderFileStatus();
        }else if(page.equals("network")){
            networkPage(body);
        }else{
            TextView resolutionTitle=ui.text(L10n.tr("被控端分辨率"),12,false);resolutionTitle.setTextColor(SECONDARY);body.addView(resolutionTitle);ui.gap(body,5);
            resolutionButton=ui.action("",INK,this::chooseResolution);resolutionButton.setTextSize(13);resolutionButton.setBackground(ui.background(SURFACE_ELEVATED,7));body.addView(resolutionButton,ui.size(-1,34));
            resolutionFeedback=ui.text("",12,false);resolutionFeedback.setTextColor(SECONDARY);resolutionFeedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(resolutionFeedback);ui.gap(body,12);renderResolutions();
            settingRows.add(new SettingRow(body,0,L10n.tr("画面质量"),new String[]{L10n.tr("低"),L10n.tr("中"),L10n.tr("高")},new int[]{0,1,2}));ui.gap(body,12);
            settingRows.add(new SettingRow(body,1,L10n.tr("画面采集帧率"),new String[]{"30 fps","60 fps"},new int[]{30,60}));ui.gap(body,12);
            settingRows.add(new SettingRow(body,2,L10n.tr("画面偏好"),RemoteVideoSettings.preferences(),new int[]{0,1,2}));renderVideoSettings();
        }
        ui.gap(menu,9);TextView disconnect=ui.action(L10n.tr("断开连接"),Color.WHITE,()->{closeMenu();closeKeyboard();actions.disconnect();});disconnect.setMinHeight(ui.dp(34));disconnect.setTextSize(14);disconnect.setBackground(ui.background(RED,8));menu.addView(disconnect,ui.size(-1,34));
        addView(menu,new LayoutParams(panelWidth(getWidth()),panelHeight(getHeight())));orb.bringToFront();orb.setContentDescription(L10n.tr("收起远程控制菜单"));
    }
    private long buildCode(){
        try{return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),0).getLongVersionCode();}
        catch(Exception ignored){return 0;}
    }
    private void renderPrivacy(){
        if(menu!=null&&menuPage.equals("controls")&&(privacyButton!=null)!=privacy.desktopAvailable){
            openMenu("controls");return;
        }
        long now=android.os.SystemClock.elapsedRealtime();privacy.tick(now);
        if(privacyFeedback!=null){String feedback=privacy.feedback(now);privacyFeedback.setText(feedback);privacyFeedback.setVisibility(feedback.isEmpty()?GONE:VISIBLE);}
        if(privacyButton==null)return;
        boolean enabled=privacy.enabled(now);String action=privacy.active()?L10n.tr("关闭隐私屏"):L10n.tr("开启隐私屏");
        privacyIcon.setName(enabled?"eye_off":"eye");
        privacyCaption.setText(privacy.buttonTitle(now));
        privacyButton.setSelected(enabled);
        privacyButton.setContentDescription(action+"，"+privacy.status(now));
        privacyButton.setEnabled(privacy.canToggle(now));privacyButton.setAlpha(privacyButton.isEnabled()?1f:.45f);
    }
    private final class SettingRow {
        final int field;final int[] values;final SegmentedControl group;
        SettingRow(LinearLayout body,int field,String title,String[] labels,int[] values){
            this.field=field;this.values=values;
            TextView label=ui.text(title,12,false);label.setTextColor(SECONDARY);body.addView(label);ui.gap(body,5);
            group=new SegmentedControl(ui,labels,selectionIndex(),settings.supported,index->actions.videoSettings(field,values[index]));
            group.setPadding(ui.dp(2),ui.dp(2),ui.dp(2),ui.dp(2));body.addView(group);
            for(int i=0;i<labels.length;i++){
                TextView button=(TextView)group.getChildAt(i);button.setTextSize(12);button.setMinHeight(ui.dp(28));button.setSingleLine();button.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);button.setContentDescription(title+"，"+labels[i]);
                button.setLayoutParams(new LinearLayout.LayoutParams(0,ui.dp(28),1));
            }
        }
        int selectionIndex(){int selected=field==0?settings.selection.quality:field==1?settings.selection.frameRate:settings.selection.preference;
            for(int i=0;i<values.length;i++)if(values[i]==selected)return i;return 0;
        }
        void render(){group.setSelection(selectionIndex());group.setOptionsEnabled(settings.supported);}
    }
    private void renderResolutions(){
        if(resolutionButton==null)return;
        RemoteDisplayResolutions.Display display=resolutions.display(resolutionDisplay);
        boolean enabled=display!=null&&!display.modes.isEmpty()&&!resolutions.pending;
        resolutionButton.setText(display==null?L10n.tr("设备不支持"):display.current.label()+" ▾");
        resolutionButton.setEnabled(enabled);resolutionButton.setAlpha(enabled?1f:.45f);
        String feedback=resolutions.feedback();resolutionFeedback.setText(feedback);resolutionFeedback.setVisibility(feedback.isEmpty()?GONE:VISIBLE);
    }
    private void chooseResolution(){
        RemoteDisplayResolutions.Display display=resolutions.display(resolutionDisplay);
        if(display==null||resolutions.pending||display.modes.isEmpty())return;
        String[] labels=new String[display.modes.size()];int selected=-1;
        for(int i=0;i<labels.length;i++){labels[i]=display.modes.get(i).label();if(display.current.same(display.modes.get(i)))selected=i;}
        resolutionDialog=new android.app.AlertDialog.Builder(getContext()).setTitle(L10n.tr("被控端分辨率"))
            .setSingleChoiceItems(labels,selected,(dialog,index)->{
                dialog.dismiss();resolutionDialog=null;
                RemoteDisplayResolutions.Display current=resolutions.display(resolutionDisplay);
                if(current!=null&&current.id==display.id&&current.key.equals(display.key))actions.displayResolution(display.modes.get(index));
            }).setNegativeButton(L10n.tr("取消"),null).show();
    }
    private void renderVideoSettings(){
        if(videoFeedback!=null){String feedback=settings.feedback();videoFeedback.setText(feedback);videoFeedback.setVisibility(feedback.isEmpty()?GONE:VISIBLE);}
        for(SettingRow row:settingRows)row.render();
    }
    private void networkPage(LinearLayout body){
        endpointsExpanded = false;
        trafficCells=new TextView[4][3];trafficRows=new LinearLayout[4];String[] names={L10n.tr("视频"),L10n.tr("音频"),L10n.tr("数据"),L10n.tr("合计")};
        LinearLayout heading=trafficRow(body,"",new TextView[3],false);((TextView)heading.getChildAt(1)).setText(L10n.tr("接收"));((TextView)heading.getChildAt(2)).setText(L10n.tr("发送"));((TextView)heading.getChildAt(3)).setText(L10n.tr("丢包率"));
        for(int i=0;i<heading.getChildCount();i++)((TextView)heading.getChildAt(i)).setTextColor(SECONDARY);
        for(int i=0;i<4;i++)trafficRows[i]=trafficRow(body,names[i],trafficCells[i],i==3);
        ui.gap(body,10);ui.divider(body);ui.gap(body,10);
        String[] titles={L10n.tr("帧率"),L10n.tr("分辨率"),L10n.tr("画面延时"),L10n.tr("连接延时（RTT）"),L10n.tr("连接方式"),L10n.tr("本端地址"),L10n.tr("对端地址"),L10n.tr("媒体加密"),L10n.tr("被控端编码器"),L10n.tr("控制端解码器")};networkDetails=new TextView[titles.length];
        for(int i=0;i<titles.length;i++){LinearLayout row=ui.row();
            boolean longValue=i>=LOCAL_ENDPOINT && i!=ENCRYPTION;
            TextView title=ui.text(titles[i],12,false);title.setTextColor(SECONDARY);row.addView(title,longValue?new LinearLayout.LayoutParams(-2,-2):new LinearLayout.LayoutParams(0,-2,1));
            if(i==ENCRYPTION){encryptionLock=ui.icon("lock",GREEN);encryptionOpen=ui.icon("unlock",SECONDARY);for(View icon:new View[]{encryptionLock,encryptionOpen}){LinearLayout.LayoutParams p=ui.size(14,14);p.rightMargin=ui.dp(4);row.addView(icon,p);}}
            TextView value=ui.text("—",12,false);value.setFontFeatureSettings("tnum");
            if(longValue){value.setGravity(Gravity.END);value.setSingleLine();value.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);value.setMarqueeRepeatLimit(-1);value.setSelected(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=ui.dp(8);row.addView(value,p);}else row.addView(value);
            if(i==CONNECTION_MODE) {
                row.setOnClickListener(v -> { endpointsExpanded=!endpointsExpanded; renderNetwork(); });
            }
            networkDetails[i]=value;
            LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
            if(i>0)layout.topMargin=ui.dp(7);
            body.addView(row,layout);
        }
        renderNetwork();
    }
    private LinearLayout trafficRow(LinearLayout body,String title,TextView[] cells,boolean total){
        LinearLayout row=ui.row();row.setPadding(ui.dp(4),ui.dp(5),ui.dp(4),ui.dp(5));body.addView(row);if(total)row.setBackground(ui.background(0x122E7ADB,5));
        TextView label=ui.text(title,11,total);row.addView(label,ui.size(28,-2));
        for(int i=0;i<3;i++){TextView cell=ui.text("—",11,total);cell.setGravity(Gravity.END);cell.setSingleLine();cell.setFontFeatureSettings("tnum");cell.setAutoSizeTextTypeUniformWithConfiguration(8,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams p=i==2?ui.size(46,16):new LinearLayout.LayoutParams(0,ui.dp(16),1);p.leftMargin=ui.dp(4);row.addView(cell,p);cells[i]=cell;}
        return row;
    }
    private void renderNetwork(){
        if(trafficCells==null)return;
        String[] names={L10n.tr("视频"),L10n.tr("音频"),L10n.tr("数据"),L10n.tr("合计")};
        for(int i=0;i<4;i++){
            RemoteNetworkStatistics.Traffic traffic=network.report==null?null:network.report.traffic[i];
            String in=RemoteNetworkStatistics.bitrate(traffic==null?-1:traffic.inbound),out=RemoteNetworkStatistics.bitrate(traffic==null?-1:traffic.outbound),loss=RemoteNetworkStatistics.loss(traffic==null?Double.NaN:traffic.loss);
            trafficCells[i][0].setText(in);trafficCells[i][1].setText(out);trafficCells[i][2].setText(loss);trafficRows[i].setContentDescription(L10n.format("{0}，接收 {1}，发送 {2}，丢包率 {3}",names[i],in,out,loss));
        }
        boolean direct=network.report!=null && network.report.mode==0
                && !network.report.localEndpoint.isEmpty() && !network.report.remoteEndpoint.isEmpty();
        if(!direct) endpointsExpanded=false;
        View modeRow=(View)networkDetails[CONNECTION_MODE].getParent();
        modeRow.setClickable(direct);
        modeRow.setFocusable(direct);
        String mode=network.mode();
        if(direct) mode+=endpointsExpanded?"  ▾":"  ▸";
        String[] values={network.fps+" FPS",network.resolution(),RemoteNetworkStatistics.latency(network.latency),
                RemoteNetworkStatistics.latency(network.rtt),mode,direct?network.report.localEndpoint:"",
                direct?network.report.remoteEndpoint:"",network.report==null?"—":network.report.srtp?L10n.tr("SRTP 已启用"):L10n.tr("未启用"),
                network.remoteEncoder.isEmpty()?"—":network.remoteEncoder,network.localDecoder.isEmpty()?"—":network.localDecoder};
        for(int i=LOCAL_ENDPOINT;i<=REMOTE_ENDPOINT;i++)
            ((View)networkDetails[i].getParent()).setVisibility(endpointsExpanded?VISIBLE:GONE);
        // Reassigning identical text every statistics tick restarts the marquee.
        for(int i=0;i<values.length;i++)if(!android.text.TextUtils.equals(networkDetails[i].getText(),values[i]))networkDetails[i].setText(values[i]);
        networkDetails[CONNECTION_MODE].setTextColor(direct?BLUE:INK);
        networkDetails[ENCRYPTION].setTextColor(network.report!=null&&network.report.srtp?GREEN:SECONDARY);
        encryptionLock.setVisibility(network.report!=null&&network.report.srtp?VISIBLE:GONE);encryptionOpen.setVisibility(network.report!=null&&!network.report.srtp?VISIBLE:GONE);
    }
    private int controlColumns(){return getMeasuredHeight()<ui.dp(400)&&getMeasuredWidth()>=ui.dp(400)?4:3;}
    private int panelWidth(int width){return Math.max(1,Math.min(width-ui.dp(16),controlColumns()==4?ui.dp(380):Math.max(ui.dp(280),Math.min(ui.dp(340),width-ui.dp(160)))));}
    private int panelHeight(int height){
        // First measure every row at its natural height, then cap only to the
        // safe viewport. The weighted ScrollView takes any remaining space.
        LinearLayout.LayoutParams scrollParams=(LinearLayout.LayoutParams)menuScroll.getLayoutParams();
        scrollParams.height=LayoutParams.WRAP_CONTENT;scrollParams.weight=0;
        menu.measure(MeasureSpec.makeMeasureSpec(panelWidth(getMeasuredWidth()),MeasureSpec.EXACTLY),
                     MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        int contentHeight=menu.getMeasuredHeight();
        scrollParams.height=0;scrollParams.weight=1;
        menu.forceLayout();menuScroll.forceLayout();
        return Math.min(contentHeight,Math.max(1,height-ui.dp(16)));
    }
    private void positionMenu(){if(menu==null)return;float ox=orb.getX()+orb.getWidth()/2f;float x=ox>getWidth()/2f?orb.getX()-ui.dp(12)-menu.getWidth():orb.getX()+orb.getWidth()+ui.dp(12);menu.setX(bound(x,ui.dp(8),getWidth()-menu.getWidth()-ui.dp(8)));menu.setY(bound(orb.getY(),ui.dp(8),getHeight()-menu.getHeight()-ui.dp(8)));}
    boolean closeKeyboard(){return keyboard.close();}
    void resetKeyboardContext(){keyboard.resetContext();}
    void keyboardInsets(int height,boolean visible,int navigation){keyboard.updateInsets(height,visible,navigation);}
    private void toggleKeyboard(){keyboard.bringToFront();keyboard.toggle();}
    private void drag(View handle,View target,Runnable tap){drag(handle,target,tap,null);}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Tap dispatches performClick; dragging is an additional action.
    private void drag(View handle,View target,Runnable tap,Runnable longPress){
        if (tap != null) handle.setOnClickListener(v -> tap.run());
        handle.setOnTouchListener(new View.OnTouchListener() {
            final int[] screen = new int[2], container = new int[2];
            float startX, startY, originX, originY;
            boolean moved, longPressed;
            int pointer = -1;
            final Runnable longPressTask = () -> {
                if (!moved && pointer != -1 && longPress != null) {
                    longPressed = true;
                    handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    longPress.run();
                }
            };

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (target == orb) cancelMouseInput();
                        pointer = event.getPointerId(0);
                        startX = event.getRawX(); startY = event.getRawY();
                        target.getLocationOnScreen(screen);
                        SessionControls.this.getLocationOnScreen(container);
                        originX = screen[0] - container[0]; originY = screen[1] - container[1];
                        moved = false;
                        longPressed = false;
                        handle.removeCallbacks(longPressTask);
                        if (longPress != null) handle.postDelayed(longPressTask, 500);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerId(0) != pointer) break;
                        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                        if (!moved && Math.hypot(dx, dy) <= ui.dp(4)) break;
                        moved = true;
                        handle.removeCallbacks(longPressTask);
                        int margin = ui.dp(8);
                        float x = bound(originX + dx, margin, getWidth() - target.getWidth() - margin);
                        float y = bound(originY + dy, margin, getHeight() - target.getHeight() - margin);
                        if (target == orb) {
                            if (menu != null) closeMenu();
                            orb.setX(x); orb.setY(y);
                        } else {
                            mouseX = x; mouseY = y;
                            positionMouse();
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP:
                        if (event.getPointerId(event.getActionIndex()) == pointer) {
                            handle.removeCallbacks(longPressTask);
                            if (!moved && !longPressed && tap != null) v.performClick();
                            pointer = -1;
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        handle.removeCallbacks(longPressTask);
                        pointer = -1;
                        break;
                }
                return true;
            }
        });
    }
    void constrainPanels(){
        cancelMouseInput();
        if(menu!=null&&menuPage.equals("controls")&&menuColumns!=controlColumns())openMenu("controls");
        orb.setX(bound(orb.getX(),ui.dp(8),getWidth()-orb.getWidth()-ui.dp(8)));orb.setY(bound(orb.getY(),ui.dp(8),getHeight()-orb.getHeight()-ui.dp(8)));
        if(menu!=null||keyboard.isOpen())requestLayout();
        positionMouse();
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        super.onMeasure(widthSpec,heightSpec);
        boolean resized=false;
        if(menu!=null){
            // Use this traversal's viewport, including rotation/inset changes, before drawing.
            ViewGroup.LayoutParams p=menu.getLayoutParams();int width=panelWidth(getMeasuredWidth()),height=panelHeight(getMeasuredHeight());
            // panelHeight performed an unconstrained measurement; always
            // measure again with the final cap, even when it did not change.
            p.width=width;p.height=height;resized=true;
        }
        if(keyboard.isOpen()){
            LayoutParams p=(LayoutParams)keyboard.getLayoutParams();
            int height=keyboard.preferredHeight(),inset=keyboard.bottomInset();
            if(p.height!=height||p.bottomMargin!=inset){p.height=height;p.bottomMargin=inset;resized=true;}
        }
        if(resized)super.onMeasure(widthSpec,heightSpec);
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        super.onLayout(changed,left,top,right,bottom);
        keyboardInsetChanged.accept(keyboard.isOpen() ? getHeight() - keyboard.getTop() : 0);
        if(!orbPositioned){orb.setX(Math.max(ui.dp(12),getWidth()-ui.dp(66)));orb.setY(ui.dp(17));orbPositioned=true;}
        orb.setX(bound(orb.getX(),ui.dp(8),getWidth()-orb.getWidth()-ui.dp(8)));orb.setY(bound(orb.getY(),ui.dp(8),getHeight()-orb.getHeight()-ui.dp(8)));
        // New/rebuilt menus have real dimensions now; position them in the same frame.
        positionMenu();
        positionMouse();
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(android.os.Build.VERSION.SDK_INT<30)getViewTreeObserver().addOnGlobalLayoutListener(legacyKeyboardLayout);}
    @Override protected void onDetachedFromWindow(){if(statsHud!=null)statsHud.removeCallbacks(durationTicker);getViewTreeObserver().removeOnGlobalLayoutListener(legacyKeyboardLayout);cancelMouseInput();super.onDetachedFromWindow();}
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);post(this::constrainPanels);}
    private static float bound(float value,float min,float max){return Math.max(min,Math.min(value,Math.max(min,max)));}
}
