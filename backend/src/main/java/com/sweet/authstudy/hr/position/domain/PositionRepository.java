package com.sweet.authstudy.hr.position.domain;

import java.util.List;
import java.util.Optional;
import com.sweet.authstudy.shared.application.PageResult;

public interface PositionRepository {

    Position save(Position position);

    Optional<Position> findByCompanyIdAndCode(long companyId, String code);

    List<Position> findAllByCompanyId(long companyId);

    PageResult<Position> search(
            long companyId, String search, Boolean active, int page, int size, String sort);
}
