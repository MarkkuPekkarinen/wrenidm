/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright © 2011 ForgeRock AS. All rights reserved.
 * Portions Copyright 2018 Wren Security.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */
package org.forgerock.openidm.repo.jdbc.impl.vendor;

import java.sql.SQLException;
import org.forgerock.openidm.repo.jdbc.ErrorType;
import org.forgerock.openidm.repo.jdbc.SQLExceptionHandler;
import org.forgerock.openidm.repo.jdbc.impl.DefaultSQLExceptionHandler;
import org.forgerock.openidm.repo.jdbc.impl.XOpenErrorMapping;

/**
 * MySQL database {@link SQLExceptionHandler} implementation.
 */
public class MySQLExceptionHandler extends DefaultSQLExceptionHandler {

    @Override
    public boolean isErrorType(SQLException ex, ErrorType errorType) {
        boolean result = XOpenErrorMapping.isErrorType(ex, errorType);

        // MySQL the 23000 status code can be for multiple constraint violations, not just duplicates
        if (ErrorType.DUPLICATE_KEY.equals(errorType) && result) {
            // MySQL 1062 is for duplicate key value
            return 1062 == ex.getErrorCode();
        }

        return result;
    }

}
