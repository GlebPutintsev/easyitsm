package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.easyitsm.identity.api.AccessScope;
import ru.easyitsm.identity.api.RecordAccess;
import ru.easyitsm.platform.id.Ids;

/** Правила ABAC (ACC-02, ACC-04) на всех сочетаниях ролей и атрибутов. */
class AccessDecisionTest {

    final UUID me = Ids.newId();
    final UUID other = Ids.newId();
    final UUID myGroup = Ids.newId();
    final UUID foreignGroup = Ids.newId();
    final UUID securityGroup = Ids.newId();
    final UUID org = Ids.newId();

    AccessScope requester() {
        return new AccessScope(me, Set.of(), false, false, null);
    }

    AccessScope agent() {
        return new AccessScope(me, Set.of(myGroup), false, true, null);
    }

    AccessScope mim() {
        return new AccessScope(me, Set.of(), true, false, null);
    }

    RecordAccess ticket(UUID requesterId, UUID group, boolean confidential) {
        return new RecordAccess(requesterId, null, group, org, Set.of(), confidential, securityGroup);
    }

    @Test
    void requesterSeesOnlyOwnRecords() {
        assertThat(AccessDecisionService.decide(requester(), ticket(me, foreignGroup, false))).isTrue();
        assertThat(AccessDecisionService.decide(requester(), ticket(other, foreignGroup, false))).isFalse();
    }

    @Test
    void watcherIsParticipant() {
        var record = new RecordAccess(other, null, foreignGroup, org, Set.of(me), true, securityGroup);
        assertThat(AccessDecisionService.decide(requester(), record)).isTrue();
    }

    @Test
    void agentSeesRecordsOfOwnGroupsOnly() {
        assertThat(AccessDecisionService.decide(agent(), ticket(other, myGroup, false))).isTrue();
        assertThat(AccessDecisionService.decide(agent(), ticket(other, foreignGroup, false))).isFalse();
        assertThat(AccessDecisionService.decide(agent(), ticket(other, null, false))).isFalse();
    }

    @Test
    void groupMembershipWithoutAgentRoleGivesNothing() {
        var scope = new AccessScope(me, Set.of(myGroup), false, false, null);
        assertThat(AccessDecisionService.decide(scope, ticket(other, myGroup, false))).isFalse();
    }

    @Test
    void confidentialOnlyForParticipantsAndSecurityGroup() {
        assertThat(AccessDecisionService.decide(agent(), ticket(other, myGroup, true))).isFalse();
        assertThat(AccessDecisionService.decide(mim(), ticket(other, myGroup, true))).isFalse();
        var securityMember = new AccessScope(me, Set.of(securityGroup), false, false, null);
        assertThat(AccessDecisionService.decide(securityMember, ticket(other, myGroup, true))).isTrue();
        assertThat(AccessDecisionService.decide(requester(), ticket(me, myGroup, true))).isTrue();
    }

    @Test
    void mimAndAuditorSeeAllNonConfidential() {
        assertThat(AccessDecisionService.decide(mim(), ticket(other, foreignGroup, false))).isTrue();
    }

    @Test
    void organizationScopeOnlyWhenPolicyEnabled() {
        var withOrg = new AccessScope(me, Set.of(), false, false, org);
        assertThat(AccessDecisionService.decide(withOrg, ticket(other, foreignGroup, false))).isTrue();
        assertThat(AccessDecisionService.decide(withOrg, ticket(other, foreignGroup, true))).isFalse();
    }

    @Test
    void anonymousSeesNothing() {
        var anonymous = new AccessScope(null, Set.of(), true, true, null);
        assertThat(AccessDecisionService.decide(anonymous, ticket(other, myGroup, false))).isFalse();
    }
}
