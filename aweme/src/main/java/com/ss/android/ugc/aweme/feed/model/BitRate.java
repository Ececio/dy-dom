package com.ss.android.ugc.aweme.feed.model;

import com.ss.android.ugc.aweme.base.model.UrlModel;

/**
 * 抖音视频清晰度档位（video.bitRate[]）。
 * 仅保留模块用到的字段，真实类由宿主提供。
 */
public class BitRate {

    public int bitRate;

    public String format;

    public String gearName;

    public int isH265;

    public Integer isBytevc1;

    public UrlModel playAddr;

    public UrlModel playAddr265;

    public int qualityType;

    public String videoExtra;

    private BitRate() {
        throw new RuntimeException("stub!!");
    }
}
