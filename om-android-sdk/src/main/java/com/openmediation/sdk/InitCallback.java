// Copyright 2020 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk;

import com.openmediation.sdk.utils.error.Error;

/**
 * SDK初始化接口回调
 */
public interface InitCallback {
    /**
     * called upon SDK init success
     */
    void onSuccess();

    /**
     * called upon SDK init failure
     *
     * @param result failure reason
     */
    void onError(Error result);
}
