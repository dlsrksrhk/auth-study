package com.sweet.authstudy.oauth.infrastructure;

import com.sweet.authstudy.oauth.application.OAuthConsentAdminQuery;
import com.sweet.authstudy.shared.application.PageResult;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OAuthConsentAdminQueryAdapter implements OAuthConsentAdminQuery {
    private static final String FROM = """
        from oauth_consent c
        join oauth_client cl on cl.id=c.registered_client_id
        join accounts a on a.id=c.principal_account_id
        join oauth_subject s on s.account_id=a.id
        where cl.company_id=:company and a.company_id=:company and c.registered_client_id=:client
        """;
    private static final String SELECT = """
        select c.principal_account_id,s.subject,c.created_at,c.updated_at,
        array(select scope from oauth_consent_scope where consent_id=c.id order by scope) scopes
        """;
    private static final RowMapper<Entry> MAPPER = (rs, row) -> new Entry(rs.getLong("principal_account_id"),
            rs.getObject("subject", UUID.class), Set.copyOf(Arrays.asList((String[])rs.getArray("scopes").getArray())),
            rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    private final NamedParameterJdbcTemplate jdbc;
    public OAuthConsentAdminQueryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public PageResult<Entry> findPage(long companyId,long clientId,int page,int size) {
        var params=new HashMap<String,Object>(Map.of("company",companyId,"client",clientId,"limit",size,"offset",(long)page*size));
        long total=jdbc.queryForObject("select count(*) "+FROM,params,Long.class);
        return new PageResult<>(jdbc.query(SELECT+FROM+" order by c.created_at desc,c.id desc limit :limit offset :offset",params,MAPPER),total,(int)((total+size-1)/size));
    }
    public Optional<Entry> find(long companyId,long clientId,UUID subject) {
        return jdbc.query(SELECT+FROM+" and s.subject=:subject",Map.of("company",companyId,"client",clientId,"subject",subject),MAPPER).stream().findFirst();
    }
}
