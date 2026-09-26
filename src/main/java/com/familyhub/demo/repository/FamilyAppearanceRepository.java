package com.familyhub.demo.repository;

import com.familyhub.demo.model.FamilyAppearance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyAppearanceRepository extends JpaRepository<FamilyAppearance, UUID> {}
