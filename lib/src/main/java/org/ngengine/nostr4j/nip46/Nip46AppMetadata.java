/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.nip46;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Nip46AppMetadata implements Cloneable, Serializable {

    private String name;
    private String url;
    private String image;
    private List<String> perms;

    public Nip46AppMetadata(String name, String url, String image, List<String> perms) {
        this.name = name;
        this.url = url;
        this.image = image;
        this.perms = perms;
    }

    public Nip46AppMetadata() {}

    public Nip46AppMetadata setName(String name) {
        this.name = name;
        return this;
    }

    public Nip46AppMetadata setUrl(String url) {
        this.url = url;
        return this;
    }

    public Nip46AppMetadata setImage(String image) {
        this.image = image;
        return this;
    }

    public Nip46AppMetadata setPerms(List<String> perms) {
        this.perms = perms;
        return this;
    }

    public Nip46AppMetadata addPerm(String perm) {
        if (this.perms == null) {
            this.perms = new ArrayList<>();
        }
        this.perms.add(perm);
        return this;
    }

    public Nip46AppMetadata removePerm(String perm) {
        if (this.perms != null) {
            this.perms.remove(perm);
        }
        if (this.perms.isEmpty()) {
            this.perms = null;
        }
        return this;
    }

    public Nip46AppMetadata permsFromCsv(String csv) {
        if (csv != null && !csv.isEmpty()) {
            this.perms = List.of(csv.split(","));
        } else {
            this.perms = new ArrayList<>();
        }
        return this;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getImage() {
        return image;
    }

    public List<String> getPerms() {
        return perms;
    }

    @Override
    public String toString() {
        return (
            "Nip46AppMetadata{" +
            "name='" +
            name +
            '\'' +
            ", url='" +
            url +
            '\'' +
            ", image='" +
            image +
            '\'' +
            ", perms=" +
            perms +
            '}'
        );
    }

    @Override
    public Nip46AppMetadata clone() throws CloneNotSupportedException {
        return (Nip46AppMetadata) super.clone();
    }
}
