package com.sap.olingo.jpa.processor.core.query;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.apache.olingo.commons.api.http.HttpStatusCode.INTERNAL_SERVER_ERROR;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Selection;

import org.apache.olingo.commons.api.ex.ODataException;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;

import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAAssociationPath;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAEntityType;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAPath;
import com.sap.olingo.jpa.metadata.core.edm.mapper.exception.ODataJPAModelException;
import com.sap.olingo.jpa.processor.core.api.JPAODataRequestContextAccess;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAQueryException;

/**
 * Requires Processor Query
 *
 * @author Oliver Grande
 * @since 1.0.1
 * 25.11.2020
 */
public final class JPAExpandJoinCountQuery extends JPAAbstractExpandQuery {
  private final Optional<JPAKeyBoundary> keyBoundary;

  public JPAExpandJoinCountQuery(final OData odata,
      final JPAODataRequestContextAccess requestContext, final JPAEntityType et,
      final JPAAssociationPath association, final List<JPANavigationPropertyInfo> hops,
      final Optional<JPAKeyBoundary> keyBoundary)
      throws ODataException {

    super(odata, requestContext, et, association, copyHops(hops));
    this.keyBoundary = keyBoundary;
  }

  private static List<JPANavigationPropertyInfo> copyHops(final List<JPANavigationPropertyInfo> hops) {
    return hops.stream()
        .map(JPANavigationPropertyInfo::new)
        .collect(Collectors.toList());
  }

  @Override
  public JPAExpandQueryResult execute() throws ODataApplicationException {
    final int handle = debugger.startRuntimeMeasurement(this, "execute");
    try {
      //
      return null;
    } finally {
      debugger.stopRuntimeMeasurement(handle);
    }
  }

  @Override
  protected Map<String, From<?, ?>> createFromClause(final List<JPAAssociationPath> orderByTarget,
      final Collection<JPAPath> selectionPath, final CriteriaQuery<?> query, final JPANavigationPropertyInfo lastInfo)
      throws ODataApplicationException, JPANoSelectionException {

    final HashMap<String, From<?, ?>> joinTables = new HashMap<>();

    createFromClauseRoot(query, joinTables, lastInfo);
    target = root;
    createFromClauseDescriptionFields(selectionPath, joinTables, target, singletonList(lastInfo));
    return joinTables;
  }

  @Override
  protected JPAAssociationPath getAssociation(@Nonnull final JPAInlineItemInfo item) {
    return item.hops.get(item.hops.size() - 2).getAssociationPath();
  }

  private void createFromClauseRoot(final CriteriaQuery<?> query, final HashMap<String, From<?, ?>> joinTables,
      final JPANavigationPropertyInfo lastInfo) throws ODataJPAQueryException {
    try {
      final JPAEntityType sourceEt = lastInfo.getEntityType();
      this.root = query.from(sourceEt.getTypeClass());
      this.lastInfo.setFromClause(root);
      joinTables.put(sourceEt.getExternalFQN().getFullQualifiedNameAsString(), root);
    } catch (final ODataJPAModelException e) {
      throw new ODataJPAQueryException(e, INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  final Map<String, Long> count() throws ODataApplicationException {
    final int handle = debugger.startRuntimeMeasurement(this, "count");

    if (countRequested(lastInfo)) {
      final CriteriaQuery<Tuple> countQuery = cb.createTupleQuery();
      createCountFrom(countQuery);
      final List<Selection<?>> selectionPath = buildExpandJoinPath(target);
      countQuery.multiselect(addCount(selectionPath));
      final javax.persistence.criteria.Expression<Boolean> whereClause = createWhere();
      if (whereClause != null)
        countQuery.where(whereClause);
      countQuery.groupBy(buildExpandCountGroupBy(target));
      final TypedQuery<Tuple> query = em.createQuery(countQuery);
      final List<Tuple> intermediateResult = query.getResultList();
      return convertCountResult(intermediateResult);
    }
    debugger.stopRuntimeMeasurement(handle);
    return emptyMap();
  }

  void createCountFrom(final CriteriaQuery<Tuple> countQuery) throws ODataJPAQueryException {
    final HashMap<String, From<?, ?>> joinTables = new HashMap<>();
    // 1. Create navigation joins
    createFromClauseRoot(countQuery, joinTables);
    target = root;
    for (int i = 0; i < this.navigationInfo.size() - 1; i++) {
      final JPANavigationPropertyInfo naviInfo = this.navigationInfo.get(i);
      naviInfo.setFromClause(target);
      target = createJoinFromPath(naviInfo.getAssociationPath().getAlias(), naviInfo.getAssociationPath().getPath(),
          target, JoinType.INNER);
    }
    lastInfo.setFromClause(target);
  }

  private Expression<Boolean> createWhere() throws ODataApplicationException {

    final int handle = debugger.startRuntimeMeasurement(this, "createWhere");
    try {
      javax.persistence.criteria.Expression<Boolean> whereCondition = null;
      // Given keys: Organizations('1')/Roles(...)
      whereCondition = createKeyWhere(navigationInfo);
      whereCondition = addWhereClause(whereCondition, createBoundary(navigationInfo, keyBoundary));
      whereCondition = addWhereClause(whereCondition, createExpandWhere());
      whereCondition = addWhereClause(whereCondition, createProtectionWhere(claimsProvider));
      return whereCondition;
    } finally {
      debugger.stopRuntimeMeasurement(handle);
    }
  }

  private javax.persistence.criteria.Expression<Boolean> createExpandWhere() throws ODataApplicationException {

    javax.persistence.criteria.Expression<Boolean> whereCondition = null;
    for (final JPANavigationPropertyInfo info : this.navigationInfo) {
      if (info.getFilterCompiler() != null) {
        try {
          whereCondition = addWhereClause(whereCondition, info.getFilterCompiler().compile());
        } catch (final ExpressionVisitException e) {
          throw new ODataJPAQueryException(ODataJPAQueryException.MessageKeys.QUERY_PREPARATION_FILTER_ERROR,
              HttpStatusCode.BAD_REQUEST, e);
        }
      }
    }
    return whereCondition;
  }
}
