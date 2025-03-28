/**
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015-2016 ForgeRock AS.
 * Portions Copyright 2023-2025 Wren Security.
 */

define([
    "jquery",
    "lodash",
    "org/forgerock/commons/ui/common/util/UIUtils",
    "org/forgerock/openidm/ui/common/delegates/ResourceDelegate",
    "org/forgerock/commons/ui/common/components/Messages",
    "bootstrap-dialog"
], function($, _, UIUtils, ResourceDelegate, messagesManager, BootstrapDialog) {
    var obj = {};

    /**
     * opens a bootstrap dialog with a selectized autocomplete field pre-populated
     * with all the taskinstance's candidate users
     *
     * @param parentView {can be any Backbone view with it's "this.model" set to the taskinstance being modified}
     * @returns {nothing}
     * @constructor
     */
    obj.showCandidateUserSelection = function (parentView) {
        var _this = parentView,
            candidateUsersQueryFilter =  _.map(_this.model.get("candidates").candidateUsers, function (user) {
                return 'userName eq "' + user + '"';
            }).join(" or ");

        if (!candidateUsersQueryFilter.length) {
            candidateUsersQueryFilter = "false";
        }

        ResourceDelegate.searchResource(candidateUsersQueryFilter, "managed/user").then(function (queryResult) {
            var candidateUsers = [{ _id: "noUserAssigned", givenName: "None", sn:"", userName:"" }].concat(queryResult.result),
                select = '<select class="form-control selectize" id="candidateUsersSelect" placeholder="' + $.t("templates.taskInstance.selectUser") + '..."></select>';

            BootstrapDialog.show({
                title: $.t("templates.taskInstance.assignTask"),
                size: 430,
                type: BootstrapDialog.TYPE_DEFAULT,
                message: select,
                onshown: function () {
                    $("#candidateUsersSelect").selectize({
                        valueField: "_id",
                        labelField: "userName",
                        searchField: ["userName","givenName","sn"],
                        create: false,
                        options: candidateUsers,
                        render: {
                            item: function (item, escape) {
                                return '<div class="item">' + item.givenName + ' ' + item.sn +
                                       '<br/> <span class="text-muted">' + item.userName + '</span></div>';
                            },
                            option: function (item, escape) {
                                return '<div class="option">' + item.givenName + ' ' + item.sn +
                                       '<br/> <span class="text-muted">' + item.userName + '</span></div>';
                            }
                        },
                        load: _.bind(function(query, callback) {
                            var queryFilter;

                            if (!query.length) {
                                return callback();
                            } else {
                                queryFilter = "userName sw \"" + query + "\" or givenName sw \"" + query + "\" or  sn sw \"" + query + "\"";
                            }

                            ResourceDelegate.searchResource(queryFilter, "managed/user").then(function (search) {
                                callback(search.result);
                            }, function() {
                                callback();
                            });
                        }, this)

                    });
                },
                buttons: [
                    {
                        label: $.t("common.form.cancel"),
                        action: function(dialogRef) {
                            dialogRef.close();
                        }
                    },
                    {
                        label: $.t("common.form.submit"),
                        cssClass: "btn-primary",
                        action: function(dialogRef) {
                            var select = $("#candidateUsersSelect"),
                                id = select.val(),
                                users = select[0].selectize.options,
                                selectedUser = users[id],
                                callback = function () {
                                    _this.render([_this.model.id], _.bind(function () {
                                        messagesManager.messages.addMessage({"message": $.t("templates.taskInstance.assignedSuccess")});
                                    }, this));
                                };

                            obj.assignTask(_this.model, selectedUser, callback);
                            dialogRef.close();
                        }
                    }
                ]
            });
        });

        /**
         * sets the assignee attribute on a taskinstance
         *
         * @param model {a taskinstance model}
         * @user {object representing the new assignee user to be set}
         * @successCallback
         * @returns {nothing}
         * @constructor
         */
        obj.assignTask = function(model, user, successCallback) {
            var assignNow = function () {
                model.set("assignee", user.userName);

                if (user._id === "noUserAssigned") {
                    model.set("assignee", null);
                }

                model.save().then(successCallback);
            };

            /*
             * before changing assignee alert the "assigner" that the user
             * being assigned does not exist in the list of candidate users
             */
            if (user._id !== "noUserAssigned" && !_.includes(model.get("candidates").candidateUsers, user.userName)) {
                UIUtils.jqConfirm($.t("templates.taskInstance.nonCanditateWarning",{ userName: user.userName }), _.bind(function() {
                    assignNow();
                }, this));
            } else {
                assignNow();
            }

        };

    };

    return obj;
});
