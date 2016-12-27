/*
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
 */

package org.forgerock.openidm.managed;

import static org.forgerock.json.JsonValue.*;
import static org.forgerock.json.resource.ResourceResponse.*;
import static org.forgerock.json.resource.Router.uriTemplate;
import static org.forgerock.openidm.util.ResourceUtil.notSupportedOnCollection;
import static org.forgerock.util.promise.Promises.*;
import static org.forgerock.util.query.QueryFilter.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.forgerock.http.routing.RoutingMode;
import org.forgerock.http.routing.UriRouterContext;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.CollectionResourceProvider;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourcePath;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Resources;
import org.forgerock.json.resource.Router;
import org.forgerock.json.resource.SortKey;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.json.resource.http.HttpUtils;
import org.forgerock.openidm.audit.util.ActivityLogger;
import org.forgerock.openidm.audit.util.Status;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.query.FieldTransformerQueryFilterVisitor;
import org.forgerock.openidm.router.IDMConnectionFactory;
import org.forgerock.openidm.smartevent.EventEntry;
import org.forgerock.openidm.smartevent.Name;
import org.forgerock.openidm.smartevent.Publisher;
import org.forgerock.services.context.Context;
import org.forgerock.util.AsyncFunction;
import org.forgerock.util.Function;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.query.QueryFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link RelationshipProvider} representing a collection (array) of relationships for the given field.
 */
class CollectionRelationshipProvider extends RelationshipProvider implements CollectionResourceProvider {

    /**
     * Setup logging for the {@link CollectionRelationshipProvider}.
     */
    private static final Logger logger = LoggerFactory.getLogger(CollectionRelationshipProvider.class);

    private static final FieldTransformerQueryFilterVisitor<Boolean, JsonPointer> VISITOR =
            new FieldTransformerQueryFilterVisitor<Boolean, JsonPointer>() {
                /**
                 * Converts relationship client object pointers to repo format.
                 *
                 * Converts /_refProperties/_id to /_id
                 * Converts /_refProperties/_rev to /_rev
                 * Converts /_ref to /secondId
                 * Converts /_refProperties/... to /properties/...
                 *
                 * @param isReverse Whether or not this is a reverse relationship
                 * @param field a {@link JsonPointer} representing the field to modify.
                 * @return a {@link JsonPointer} representing the modified field
                 */
                @Override
                protected JsonPointer transform(Boolean isReverse, JsonPointer field) {
                    // /_revProperties/_id to /_id
                    if (FIELD_ID.equals(field)) {
                        return new JsonPointer(FIELD_CONTENT_ID);
                    }
                    // /_refProperties/_rev to /_rev
                    if (FIELD_REV.equals(field)) {
                        return new JsonPointer(FIELD_CONTENT_REVISION);
                    }

                    // /_ref to /firstId or /secondId
                    if (FIELD_REFERENCE.equals(field)) {
                        return new JsonPointer(isReverse ? REPO_FIELD_FIRST_ID : REPO_FIELD_SECOND_ID);
                    }

                    // /_refProperties/... to /properties/...
                    if (FIELD_PROPERTIES.leaf().equals(field.get(0))) {
                        JsonPointer ptr = new JsonPointer(REPO_FIELD_PROPERTIES);
                        for (String s : field.relativePointer(field.size() - 1)) {
                            ptr = ptr.child(s);
                        }
                        return ptr;
                    }

                    // TODO: OPENIDM-4153 don't expose direct repo properties
                    return field;
                }
            };
    
    private final RequestHandler requestHandler;

    /**
     * Create a new relationship set for the given managed resource
     * @param connectionFactory Connection factory used to access the repository
     * @param resourcePath Name of the resource we are handling relationships for eg. managed/user
     * @param schemaField The schema of the field representing this relationship in the parent object.
     * @param activityLogger The audit activity logger to use
     * @param managedObjectSyncService Service to send sync events to
     */
    public CollectionRelationshipProvider(final IDMConnectionFactory connectionFactory, final ResourcePath resourcePath,
            final SchemaField schemaField, final ActivityLogger activityLogger,
            final ManagedObjectSetService managedObjectSyncService, final RelationshipValidator relationshipValidator) {
        super(connectionFactory, resourcePath, schemaField, activityLogger,
                managedObjectSyncService, relationshipValidator);

        final Router router = new Router();
        router.addRoute(RoutingMode.STARTS_WITH, 
                uriTemplate(String.format("{%s}/%s", PARAM_MANAGED_OBJECT_ID, schemaField.getName())),
                Resources.newHandler(this));
        this.requestHandler = router;
    }

    /** {@inheritDoc} */
    @Override
    public RequestHandler asRequestHandler() {
        return requestHandler;
    }

    /** {@inheritDoc} */
    @Override
    public Promise<JsonValue, ResourceException> getRelationshipValueForResource(final Context context, final String resourceId) {
        EventEntry measure = Publisher.start(Name.get("openidm/internal/relationship/collection/getRelationshipValueForResource"), resourceId, context);

        try {
            final QueryRequest queryRequest = Requests.newQueryRequest("")
                    .setAdditionalParameter(PARAM_MANAGED_OBJECT_ID, resourceId)
                    .setQueryId(RELATIONSHIP_QUERY_ID);
            final List<ResourceResponse> relationships = new ArrayList<>();

            queryCollection(new ManagedObjectContext(context), queryRequest, new QueryResourceHandler() {
                @Override
                public boolean handleResource(ResourceResponse resourceResponse) {
                    relationships.add(resourceResponse);
                    return true;
                }
            }).getOrThrowUninterruptibly(); // call get() so we block until we have all items

            final JsonValue buf = json(array());

            for (ResourceResponse resource : relationships) {
                buf.add(resource.getContent().getObject());
            }

            return newResultPromise(buf);
        } catch (ResourceException e) {
            return e.asPromise();
        } finally {
            measure.end();
        }
    }

    @Override
    public Promise<JsonValue, ResourceException> setRelationshipValueForResource(final boolean clearExisting, Context context, String resourceId,
            JsonValue relationships) {
        EventEntry measure = Publisher.start(Name.get("openidm/internal/relationship/collection/setRelationshipValueForResource"), resourceId, context);

        try {
            relationships.expect(List.class);

            // Set of relationship IDs for updating (don't delete)
            final Set<String> relationshipsToKeep = new HashSet<>();

            // Set of relationships to perform an update on (have an _id)
            final List<JsonValue> relationshipsToUpdate = new ArrayList<>();

            // Set of relationships to create (no _id field)
            final List<JsonValue> relationshipsToCreate = new ArrayList<>();

            // JsonValue array to contain persisted relations
            final JsonValue results = json(array());

            try {
                if (relationships.isNotNull() && !relationships.asList().isEmpty()) {
                    // Split relationships in to to-be-updated (_id present) and to-be-created
                    for (JsonValue relationship : relationships) {
                        final JsonValue id =
                                relationship.get(FIELD_ID);
                        if (id != null && id.isNotNull()) { // need update
                            relationshipsToUpdate.add(relationship);
                            relationshipsToKeep.add(id.asString());
                        } else { // no id. create
                            relationshipsToCreate.add(relationship);
                        }
                    }

                    if (clearExisting) {
                        // Call get() so we block until they are deleted.
                        clearNotIn(context, resourceId, relationshipsToKeep).getOrThrowUninterruptibly();
                    }
                } else {
                    // We didn't get any relations to persist. Clear and return empty array.
                    if (clearExisting) {
                        clear(context, resourceId);
                    }
                    return newResultPromise(results);
                }

                /*
                 * Create or update relationships
                 */

                // List of promises returned by update and create to when() on later
                final List<Promise<ResourceResponse, ResourceException>> promises = new ArrayList<>();

                for (JsonValue toUpdate : relationshipsToUpdate) {
                    final UpdateRequest updateRequest = Requests.newUpdateRequest("", toUpdate)
                            .setAdditionalParameter(PARAM_MANAGED_OBJECT_ID, resourceId);
                    promises.add(updateInstance(context, toUpdate.get(FIELD_ID).asString(), updateRequest));
                }

                for (JsonValue toCreate : relationshipsToCreate) {
                    final CreateRequest createRequest = Requests.newCreateRequest("", toCreate)
                            .setAdditionalParameter(PARAM_MANAGED_OBJECT_ID, resourceId);
                    promises.add(createInstance(context, createRequest));
                }

                return when(promises).then(new Function<List<ResourceResponse>, JsonValue, ResourceException>() {
                    @Override
                    public JsonValue apply(List<ResourceResponse> responses) throws ResourceException {
                        final JsonValue value = json(array());
                        for (final ResourceResponse response : responses) {
                            value.add(response.getContent().getObject());
                        }
                        return value;
                    }
                });
            } catch (ResourceException e) {
                return e.asPromise();
            }
        } finally {
            measure.end();
        }
    }

    /**
     * Clear all relationships not present in {@code relationshipsToKeep}.
     *
     * @param context The current context.
     * @param resourceId The resource whose relationships we wish to clear
     * @param relationshipsToKeep Set of relationship ids that should not be deleted
     * @return A promised JsonValue array of delete responses
     */
    private Promise<JsonValue, ResourceException> clearNotIn(final Context context, final String resourceId,
            final Set<String> relationshipsToKeep) {
        EventEntry measure = Publisher.start(Name.get("openidm/internal/relationship/collection/clearNotIn"), resourceId, context);

        try {
            return getRelationshipValueForResource(context, resourceId).thenAsync(new AsyncFunction<JsonValue, JsonValue, ResourceException>() {
                @Override
                public Promise<JsonValue, ResourceException> apply(JsonValue existingRelationships) throws ResourceException {
                    final List<Promise<ResourceResponse, ResourceException>> promises = new ArrayList<>();

                    for (JsonValue relationship : existingRelationships) {
                        final String id = relationship.get(FIELD_ID).asString();

                        // Delete if we're not told to keep this id
                        if (!relationshipsToKeep.contains(id)) {
                            final DeleteRequest deleteRequest = Requests.newDeleteRequest("", id)
                                    .setAdditionalParameter(PARAM_MANAGED_OBJECT_ID, resourceId);
                            promises.add(deleteInstance(context, id, deleteRequest));
                        }
                    }

                    return when(promises).then(new Function<List<ResourceResponse>, JsonValue, ResourceException>() {
                        @Override
                        public JsonValue apply(List<ResourceResponse> resourceResponses) throws ResourceException {
                            final JsonValue result = json(array());
                            for (ResourceResponse resourceResponse : resourceResponses) {
                                result.add(resourceResponse.getContent());
                            }
                            return result;
                        }
                    });
                }
            });
        } finally {
            measure.end();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Promise<JsonValue, ResourceException> clear(final Context context, final String resourceId) {
        EventEntry measure = Publisher.start(Name.get("openidm/internal/relationship/collection/clear"), resourceId, null);

        try {
            /*
             * FIXME - Performance here is terrible. We read every relationship just to get the id to delete.
             *         Need a deleteByQueryFilter() to remove all relationships for a given firstId
             */
            return getRelationshipValueForResource(context, resourceId).thenAsync(new AsyncFunction<JsonValue, JsonValue, ResourceException>() {
                @Override
                public Promise<JsonValue, ResourceException> apply(JsonValue existing) throws ResourceException {
                    final List<Promise<ResourceResponse, ResourceException>> deleted = new ArrayList<>();

                    for (JsonValue relationship : existing) {
                        final DeleteRequest deleteRequest = Requests.newDeleteRequest("")
                                .setAdditionalParameter(PARAM_MANAGED_OBJECT_ID, resourceId);
                        deleted.add(deleteInstance(context, relationship.get(FIELD_ID).asString(), deleteRequest));
                    }

                    return when(deleted).then(new Function<List<ResourceResponse>, JsonValue, ResourceException>() {
                        @Override
                        public JsonValue apply(List<ResourceResponse> resourceResponses) throws ResourceException {
                            final JsonValue deleted = json(array());
                            for (ResourceResponse resourceResponse : resourceResponses) {
                                deleted.add(resourceResponse.getContent());
                            }
                            return deleted;
                        }
                    });
                }
            });
        } finally {
            measure.end();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Promise<ActionResponse, ResourceException> actionCollection(Context context, ActionRequest request) {
        return notSupportedOnCollection(request).asPromise();
    }

    /** {@inheritDoc} */
    @Override
    public Promise<QueryResponse, ResourceException> queryCollection(final Context context, final QueryRequest request, 
            final QueryResourceHandler handler) {
        try {
            if (request.getQueryExpression() != null) {
                return new BadRequestException(HttpUtils.PARAM_QUERY_EXPRESSION + " not supported").asPromise();
            }

            /*
             * Create new request copying all attributes but fields.
             * This must be done so field filtering can be handled by CREST externally on the transformed response.
             */
            ResourcePath resourcePath = firstResourcePath(context, request);
            
            final QueryRequest queryRequest = Requests.newQueryRequest(REPO_RESOURCE_PATH);
            final boolean queryAllIds = ServerConstants.QUERY_ALL_IDS.equals(request.getQueryId());

            if (request.getQueryId() != null) {
                if (ServerConstants.QUERY_ALL_IDS.equals(request.getQueryId()) 
                        || "query-all".equals(request.getQueryId())) {
                    // Do nothing, the queryFilter generated below will return all
                } else if (RELATIONSHIP_QUERY_ID.equals(request.getQueryId())) {
                    // Optimized query
                    queryRequest.setQueryId(RELATIONSHIP_QUERY_ID)
                        .setAdditionalParameter(QUERY_FIELD_RESOURCE_PATH, resourcePath.toString())
                        .setAdditionalParameter(QUERY_FIELD_FIELD_NAME, schemaField.getName());
                } else {
                    return new BadRequestException("Invalid " + HttpUtils.PARAM_QUERY_ID 
                            + ": only query-all and query-all-ids supported").asPromise();
                }
            }

            queryRequest.setPageSize(request.getPageSize());
            queryRequest.setPagedResultsOffset(request.getPagedResultsOffset());
            queryRequest.setPagedResultsCookie(request.getPagedResultsCookie());
            queryRequest.setTotalPagedResultsPolicy(request.getTotalPagedResultsPolicy());
            queryRequest.addSortKey(request.getSortKeys().toArray(new SortKey[request.getSortKeys().size()]));
            for (String key : request.getAdditionalParameters().keySet()) {
                queryRequest.setAdditionalParameter(key, request.getAdditionalParameter(key));
            }
            
            // Check if using a queryId, if not build up the queryFilter
            if (queryRequest.getQueryId() == null) {
                QueryFilter<JsonPointer> filter;
                if (schemaField.isReverseRelationship()) {
                    // Reverse relationship requires a queryFilter that matches both cases where the managed object's 
                    // resource path and schema field match the firstId/firstPropertyName or the 
                    // secondId/secondPropertyName.
                    QueryFilter<JsonPointer> firstFilter = and(
                            equalTo(new JsonPointer(REPO_FIELD_FIRST_ID), resourcePath),
                            equalTo(new JsonPointer(REPO_FIELD_FIRST_PROPERTY_NAME), schemaField.getName()));
                    QueryFilter<JsonPointer> secondFilter = and(
                            equalTo(new JsonPointer(REPO_FIELD_SECOND_ID), resourcePath),
                            equalTo(new JsonPointer(REPO_FIELD_SECOND_PROPERTY_NAME), schemaField.getName()));
                    if (request.getQueryFilter() != null) {
                        // AND the supplied queryFilter to both of the above generated filters and then OR them 
                        filter = or(and(firstFilter, asRelationshipQueryFilter(false, request.getQueryFilter())),
                                and(secondFilter, asRelationshipQueryFilter(true, request.getQueryFilter())));
                    } else {
                        // OR the filters together
                        filter = or(firstFilter, secondFilter);
                    }
                } else {
                    // A direct relationship requires a queryFilter that matches only the case where the managed 
                    // object's resource path and schema field match the firstId/firstPropertyName.
                    filter = and(equalTo(new JsonPointer(REPO_FIELD_FIRST_ID), resourcePath),
                            equalTo(new JsonPointer(REPO_FIELD_FIRST_PROPERTY_NAME), schemaField.getName()));
                    if (request.getQueryFilter() != null) {
                        filter = and(filter, asRelationshipQueryFilter(schemaField.isReverseRelationship(),
                                request.getQueryFilter()));
                    }
                }
                queryRequest.setQueryFilter(filter);
            }
            
            // Issue the query and handle the response
            final Promise<QueryResponse, ResourceException> response = getConnection().queryAsync(context, queryRequest, 
                    new QueryResourceHandler() {
                @Override
                public boolean handleResource(ResourceResponse resource) {
                    ResourceResponse filteredResourceResponse = 
                            formatResponseNoException(context, request).apply(resource);
                    if (queryAllIds) {
                        // Special case, return just the ids, no expansion
                        filteredResourceResponse.addField(FIELD_ID);
                        return handler.handleResource(filteredResourceResponse);
                    }
                    try {
                        filteredResourceResponse = 
                                expandFields(context, request, filteredResourceResponse).getOrThrow();
                    } catch (Exception e) {
                        logger.error("Error expanding resource: " + e.getMessage(), e);
                    }
                    return handler.handleResource(filteredResourceResponse);
                }
            });
            
            if (context.containsContext(ManagedObjectContext.class)) {
                return response;   
            }
            
            QueryResponse result = response.getOrThrow();
            
            // Get the value of the managed object
            final ResourceResponse value = getManagedObject(context);
            
            // Do activity logging.
            activityLogger.log(context, request, "query", getManagedObjectPath(context), null, value.getContent(), 
                    Status.SUCCESS);
            
            return newResultPromise(result);
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException(e.getMessage(), e).asPromise();
        }
    }

    static QueryFilter<JsonPointer> asRelationshipQueryFilter(Boolean isReverse, QueryFilter<JsonPointer> filter) {
        return filter.accept(VISITOR, isReverse);
    }

    /**
     * Implemented to iterate through the collection calling validateRelationship for each relationship within the
     * relationshipField.
     *
     * @param context context of the original request.
     * @param oldValue old value of relationship field to validate
     * @param newValue new value of relationship field to validate
     * @param referrerId the id of the object 'hosting' the relationships, aka the referrer; used to check whether
     *                   the referred-to object specified by the relationship already contains a reference to this referrer
     * @param performDuplicateAssignmentCheck set to true if invocation state should be compared to repository state to determine if
     *                                        existing relationships are specified in the invocation
     * @throws BadRequestException when the relationship isn't valid, ResourceException otherwise.
     * @see RelationshipValidator#validateRelationship(JsonValue, ResourcePath, Context, boolean)
     */
    public void validateRelationshipField(Context context, JsonValue oldValue, JsonValue newValue, ResourcePath referrerId,
              boolean performDuplicateAssignmentCheck) throws ResourceException {
        /*
        This check was added to explicitly exclude requests which specify duplicate relationships. However, the newValue
        will have the existing relationships, and the to-be-added relationship(s). In that sense, the check seems
        to duplicate the checks made the the blocks below: in both cases, we check to see whether the new relationship
        duplicates an existing relationship. However, the check in the blocks below would catch the case in which a
        request did not specify the complete set of relationship fields, and thus the newValue does not have a complete
        set of existing relationships. Checking the request-resident relationship state for duplication prior to
        invoking relationshipValidator#validateRelationship allows the more expensive request to be performed only when
        necessary.
         */
        relationshipValidator.checkForDuplicateRelationshipsInInvocationState(newValue);

        /*
        Note that not re-checking existing relationships is not just a performance optimization. If existing collection
        relationships are re-checked, then the invocation will be rejected because the relationship currently exists.
         */
        Set<RelationshipEqualityHash> oldReferences = new HashSet<>();
        if (oldValue.isNotNull()) {
            for (JsonValue oldItem : oldValue) {
                oldReferences.add(new RelationshipEqualityHash(oldItem));
            }
        }
        for (JsonValue newItem : newValue) {
            // If the relationship is found in the existing/old relationships, then must skip validation.
            if (!oldReferences.contains(new RelationshipEqualityHash(newItem))) {
                logger.debug("validating new relationship {} for {}: ", newItem, propertyPtr);
                relationshipValidator.validateRelationship(newItem, referrerId, context, performDuplicateAssignmentCheck);
            }
        }
    }

    /** {@inheritDoc} */
    String getManagedObjectId(Context context) {
        /*
        Resources.newHandler, called in this class' ctor to register the subclass on the router, will create an an internal Router
        with a hierarchy of routes to allow a CollectionResourceProvider to handle routes to the collection, and to instances
        within the collection. This additional Router results in an additional UriRouterContext on the Context stack. The
        InterfaceCollectionHandler will pop this UriRouterContext off of the stack prior to making the invocation to the
        RequestHandler, as the {managedObjectId} set in the UriTemplateVariables of this UriRouterContext is passed as the instance id
        to the CollectionResourceProvider methods. This pattern is not followed for the InterfaceSingletonHandler, and thus
        the UriTemplateVariables referencing the managed object id are in the parent of the outer-most UriRouterContext.
        The implementation of this abstract method allows the CollectionRelationshipProvider and SingletonRelationshipProvider to handle this
        difference.
         */
        return context.asContext(UriRouterContext.class)
                .getUriTemplateVariables()
                .get(PARAM_MANAGED_OBJECT_ID);
    }
}
