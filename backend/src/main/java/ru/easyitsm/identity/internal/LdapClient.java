package ru.easyitsm.identity.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.support.SingleContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Component;

/**
 * Каталог LDAP: AD, FreeIPA, ALD Pro, OpenLDAP (SEC-01, раздел 8.4 ТЗ). Проверка пароля привязкой (bind)
 * от имени пользователя и постраничная выгрузка пользователей и групп для синхронизации.
 */
@Component
class LdapClient {

    private static final Logger log = LoggerFactory.getLogger(LdapClient.class);
    private static final int PAGE_SIZE = 500;

    private final IdentityProperties.Ldap props;
    private final LdapContextSource contextSource;
    private final LdapTemplate template;

    LdapClient(IdentityProperties props) {
        this.props = props.ldap();
        if (!this.props.enabled()) {
            this.contextSource = null;
            this.template = null;
            return;
        }
        var source = new LdapContextSource();
        source.setUrl(this.props.url());
        source.setBase(this.props.base());
        if (this.props.bindDn() != null && !this.props.bindDn().isBlank()) {
            source.setUserDn(this.props.bindDn());
            source.setPassword(this.props.bindPassword());
        } else {
            source.setAnonymousReadOnly(true);
        }
        Map<String, Object> env = new HashMap<>();
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "30000");
        if ("objectGUID".equalsIgnoreCase(this.props.externalIdAttribute())) {
            env.put("java.naming.ldap.attributes.binary", "objectGUID");
        }
        source.setBaseEnvironmentProperties(env);
        source.setPooled(false);
        source.afterPropertiesSet();
        this.contextSource = source;
        this.template = new LdapTemplate(source);
        this.template.setIgnorePartialResultException(true);
    }

    record LdapUser(String dn, String externalId, String login, String email, String displayName) {
    }

    record LdapGroup(String dn, String name, Set<String> memberDns) {
    }

    boolean enabled() {
        return props.enabled();
    }

    /** Проверяет пароль привязкой. Пустой пароль не принимается: иначе сервер разрешит анонимную привязку. */
    Optional<LdapUser> authenticate(String login, String password) {
        if (!enabled() || password == null || password.isEmpty() || login == null || login.isBlank()) {
            return Optional.empty();
        }
        try {
            List<LdapUser> found = template.search("", userFilter(login), userControls(), userMapper());
            if (found.size() != 1) {
                return Optional.empty();
            }
            LdapUser user = found.getFirst();
            contextSource.getContext(user.dn(), password).close();
            return Optional.of(user);
        } catch (org.springframework.ldap.AuthenticationException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Ошибка проверки пароля в LDAP: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    List<LdapUser> allUsers() {
        return pagedSearch(props.userFilter().replace("{0}", "*"), userControls(), userMapper());
    }

    /** DN отключённых пользователей по {@code disabled-filter}, например для AD — бит ACCOUNTDISABLE. */
    Set<String> disabledDns() {
        if (props.disabledFilter() == null || props.disabledFilter().isBlank()) {
            return Set.of();
        }
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[] {"1.1"});
        String filter = "(&" + props.userFilter().replace("{0}", "*") + props.disabledFilter() + ")";
        return new HashSet<>(pagedSearch(filter, controls,
                (ContextMapper<String>) ctx -> ((DirContextAdapter) ctx).getNameInNamespace().toLowerCase()));
    }

    List<LdapGroup> allGroups() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[] {props.groupNameAttribute(), props.memberAttribute()});
        return pagedSearch(props.groupFilter(), controls, (ContextMapper<LdapGroup>) ctx -> {
            DirContextAdapter a = (DirContextAdapter) ctx;
            String[] members = a.getStringAttributes(props.memberAttribute());
            Set<String> memberDns = new HashSet<>();
            if (members != null) {
                for (String m : members) {
                    memberDns.add(m.toLowerCase());
                }
            }
            return new LdapGroup(a.getNameInNamespace().toLowerCase(), a.getStringAttribute(props.groupNameAttribute()),
                    memberDns);
        });
    }

    private <T> List<T> pagedSearch(String filter, SearchControls controls, ContextMapper<T> mapper) {
        return SingleContextSource.doWithSingleContext(contextSource, ops -> {
            List<T> result = new ArrayList<>();
            var processor = new PagedResultsDirContextProcessor(PAGE_SIZE);
            do {
                result.addAll(ops.search("", filter, controls, mapper, processor));
            } while (processor.hasMore());
            return result;
        });
    }

    private String userFilter(String login) {
        return props.userFilter().replace("{0}", LdapEncoder.filterEncode(login));
    }

    private SearchControls userControls() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[] {props.loginAttribute(), props.emailAttribute(),
                props.displayNameAttribute(), props.externalIdAttribute()});
        return controls;
    }

    private ContextMapper<LdapUser> userMapper() {
        return ctx -> {
            DirContextAdapter a = (DirContextAdapter) ctx;
            Object rawId = a.getObjectAttribute(props.externalIdAttribute());
            String externalId = rawId instanceof byte[] bytes ? HexFormat.of().formatHex(bytes)
                    : rawId != null ? rawId.toString() : a.getNameInNamespace().toLowerCase();
            String login = a.getStringAttribute(props.loginAttribute());
            String name = a.getStringAttribute(props.displayNameAttribute());
            return new LdapUser(a.getNameInNamespace().toLowerCase(), externalId, login,
                    a.getStringAttribute(props.emailAttribute()), name == null || name.isBlank() ? login : name);
        };
    }
}
