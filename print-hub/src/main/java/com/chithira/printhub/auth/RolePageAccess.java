package com.chithira.printhub.auth;

import com.chithira.printhub.common.PageKey;
import jakarta.persistence.*;

/**
 * One cell of the page-access matrix: whether a role may view and/or edit a page.
 */
@Entity
@Table(name = "role_page_access")
public class RolePageAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "page_key", nullable = false)
    private PageKey pageKey;

    @Column(name = "can_view")
    private boolean canView;

    @Column(name = "can_edit")
    private boolean canEdit;

    protected RolePageAccess() {}

    public RolePageAccess(Role role, PageKey pageKey, boolean canView, boolean canEdit) {
        this.role = role;
        this.pageKey = pageKey;
        set(canView, canEdit);
    }

    /** Edit always includes view. */
    public void set(boolean view, boolean edit) {
        this.canEdit = edit;
        this.canView = view || edit;
    }

    public Role getRole() { return role; }
    public PageKey getPageKey() { return pageKey; }
    public boolean isCanView() { return canView; }
    public boolean isCanEdit() { return canEdit; }
}
