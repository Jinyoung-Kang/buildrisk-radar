package com.buildrisk.radar.adapters.applyhome;

import com.buildrisk.radar.adapters.common.QuotaExceededException;

import java.io.Serial;

/**
 * 200 OK · totalCount 0 · data [] — 데이터셋 전체가 빈 응답(VERIFICATION 216). 기본은 한도와 같이 멈춤(이 타입은 QuotaExceededException),
 * 화면 표시에만 쓰는 잔여세대 단계만 그 달을 건너뛰고 뒤 단계를 계속함 (VERIFICATION 224).
 */
public class ApplyhomeDatasetEmptyException extends QuotaExceededException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ApplyhomeDatasetEmptyException(String message) { super(message, true); }
}
