package com.roam.service;

import com.roam.domain.HitlReview;
import com.roam.domain.ReviewType;

public interface ReviewDecisionHandler {

    ReviewType supports();

    void onDecision(HitlReview review);
}
