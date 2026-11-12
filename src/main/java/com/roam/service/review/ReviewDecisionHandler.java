package com.roam.service.review;

import com.roam.domain.review.HitlReview;
import com.roam.domain.review.ReviewType;

public interface ReviewDecisionHandler {

    ReviewType supports();

    void onDecision(HitlReview review);
}
