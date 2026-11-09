package com.cinebot.screen;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreenRepository extends JpaRepository<Screen, Long> {

    List<Screen> findByTheatreIdOrderByName(Long theatreId);
}
