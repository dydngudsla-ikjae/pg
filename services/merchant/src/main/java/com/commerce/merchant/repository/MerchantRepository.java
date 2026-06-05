package com.commerce.merchant.repository;

import com.commerce.merchant.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findTopByMerchantNoStartingWithOrderByMerchantNoDesc(String prefix);
}