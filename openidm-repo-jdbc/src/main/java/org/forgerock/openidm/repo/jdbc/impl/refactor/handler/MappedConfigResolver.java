/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.1.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.1.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2024 Wren Security
 */
package org.forgerock.openidm.repo.jdbc.impl.refactor.handler;

import org.forgerock.json.JsonPointer;

/**
 * Property pointer to mapped table column configuration resolver.
 */
@FunctionalInterface
public interface MappedConfigResolver {

    /**
     * Resolve column configuration for the given JSON property pointer.
     *
     * @param property property pointer
     * @return mapped table column configuration
     * @throws IllegalArgumentException when there is no column configuration for the given pointer
     */
    MappedColumnConfig resolve(JsonPointer property);

}
