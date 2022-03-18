package org.jenkins.ci.plugins.jobimport.model;

import java.util.Map;

import com.google.common.base.Objects;
import org.apache.commons.lang.ObjectUtils;
import org.jenkins.ci.plugins.jobimport.utils.RemoteItemUtils;

import java.io.Serializable;

public abstract class RemoteItem implements Serializable, Comparable<RemoteItem> {
    protected final String name;
    protected final String fullName;
    protected final String impl;
    protected final String url;
    protected final String description;
    
    protected Map<String, String> plugins;

    protected final RemoteFolder parent;

    protected RemoteItem(String name, String impl, String url, String description, RemoteFolder parent) {
        this.name = name;
        this.impl = impl;
        this.url = url;
        this.description = RemoteItemUtils.cleanRemoteString(description);

        this.parent = parent;

        this.fullName = getFullName();
    }

    public abstract boolean isFolder();

    public String getName() {
        return name;
    }

    public String getFullName() {
        return RemoteItemUtils.fullName(this);
    }

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    public RemoteFolder getParent() {
        return parent;
    }

    public boolean hasParent(){
        return parent != null;
    }

    public String getPlugins() {
      return plugins.toString();
    }

    public void setPlugins(final Map<String, String> plugins) {
      this.plugins = plugins;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getFullName(), impl);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RemoteItem) {
            return Objects.equal(impl, ((RemoteItem)obj).impl) &&
                    Objects.equal(getFullName(), ((RemoteItem)obj).getFullName());
        }
        return false;
    }

    @Override
    public int compareTo(RemoteItem o) {
        if (equals(o)) {
            return 0;
        }

        return getFullName().compareTo(o.getFullName());
    }
}
