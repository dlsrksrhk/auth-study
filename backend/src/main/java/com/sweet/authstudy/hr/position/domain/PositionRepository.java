package com.sweet.authstudy.hr.position.domain;

import java.util.List;
import java.util.Optional;

public interface PositionRepository {

    Position save(Position position);

    Optional<Position> findByCompanyIdAndCode(long companyId, String code);

    List<Position> findAllByCompanyId(long companyId);
}
