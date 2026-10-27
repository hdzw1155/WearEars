package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 设置页: 界面风格 / 磁贴样式 / 语言. 三组都是二选一, 点了立即写偏好.
 * 主界面在 onResume 里对比偏好变化后自己 recreate, 所以这里点完直接结束即可,
 * 但语言变了需要本页也重绘一次, 所以先 recreate 再让用户点返回.
 */
public class SettingsActivity extends Activity {

    private TextView styleNew;
    private TextView styleClassic;
    private TextView styleLiquid;
    private TextView tileNew;
    private TextView tileLiquid;
    private TextView tileClassic;
    private TextView langZh;
    private TextView langEn;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        styleNew = (TextView) findViewById(R.id.style_new);
        styleClassic = (TextView) findViewById(R.id.style_classic);
        styleLiquid = (TextView) findViewById(R.id.style_liquid);
        tileNew = (TextView) findViewById(R.id.tile_new);
        tileLiquid = (TextView) findViewById(R.id.tile_liquid);
        tileClassic = (TextView) findViewById(R.id.tile_classic);
        langZh = (TextView) findViewById(R.id.lang_zh);
        langEn = (TextView) findViewById(R.id.lang_en);

        styleNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setUiStyle(SettingsActivity.this, AppPrefs.STYLE_NEW);
                refreshChips();
            }
        });
        styleClassic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setUiStyle(SettingsActivity.this, AppPrefs.STYLE_CLASSIC);
                refreshChips();
            }
        });
        styleLiquid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setUiStyle(SettingsActivity.this, AppPrefs.STYLE_LIQUID);
                refreshChips();
            }
        });
        tileNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setTileStyle(SettingsActivity.this, AppPrefs.STYLE_NEW);
                refreshChips();
                TileStore.push(SettingsActivity.this);
            }
        });
        tileLiquid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setTileStyle(SettingsActivity.this, AppPrefs.STYLE_LIQUID);
                refreshChips();
                TileStore.push(SettingsActivity.this);
            }
        });
        tileClassic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setTileStyle(SettingsActivity.this, AppPrefs.STYLE_CLASSIC);
                refreshChips();
                TileStore.push(SettingsActivity.this);
            }
        });
        langZh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setLang(SettingsActivity.this, AppPrefs.LANG_ZH);
                recreate();
            }
        });
        langEn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppPrefs.setLang(SettingsActivity.this, AppPrefs.LANG_EN);
                recreate();
            }
        });

        refreshChips();
    }

    private void refreshChips() {
        int style = AppPrefs.uiStyle(this);
        setChoice(styleNew, style == AppPrefs.STYLE_NEW);
        setChoice(styleClassic, style == AppPrefs.STYLE_CLASSIC);
        setChoice(styleLiquid, style == AppPrefs.STYLE_LIQUID);
        int tileStyle = AppPrefs.tileStyle(this);
        setChoice(tileNew, tileStyle == AppPrefs.STYLE_NEW);
        setChoice(tileLiquid, tileStyle == AppPrefs.STYLE_LIQUID);
        setChoice(tileClassic, tileStyle == AppPrefs.STYLE_CLASSIC);
        boolean zh = AppPrefs.LANG_ZH.equals(AppPrefs.lang(this));
        setChoice(langZh, zh);
        setChoice(langEn, !zh);
    }

    private void setChoice(TextView chip, boolean selected) {
        if (chip == null) {
            return;
        }
        chip.setBackgroundResource(selected ? R.drawable.bg_choice_on : R.drawable.bg_choice);
        chip.setTextColor(getResources().getColor(selected ? R.color.text : R.color.text_dim));
    }

    /** 供入口调用: 从主界面或磁贴打开设置 */
    static Intent intent(Context ctx) {
        return new Intent(ctx, SettingsActivity.class);
    }
}
