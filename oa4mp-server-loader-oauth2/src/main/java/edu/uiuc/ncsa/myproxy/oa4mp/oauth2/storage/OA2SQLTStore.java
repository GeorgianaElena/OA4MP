package edu.uiuc.ncsa.myproxy.oa4mp.oauth2.storage;

import edu.uiuc.ncsa.myproxy.oa4mp.oauth2.OA2ServiceTransaction;
import edu.uiuc.ncsa.myproxy.oa4mp.oauth2.servlet.RFC8628State;
import edu.uiuc.ncsa.myproxy.oa4mp.server.admin.transactions.DSSQLTransactionStore;
import edu.uiuc.ncsa.security.core.Identifier;
import edu.uiuc.ncsa.security.core.exceptions.GeneralException;
import edu.uiuc.ncsa.security.core.exceptions.TransactionNotFoundException;
import edu.uiuc.ncsa.security.core.util.StringUtils;
import edu.uiuc.ncsa.security.delegation.token.RefreshToken;
import edu.uiuc.ncsa.security.delegation.token.TokenForge;
import edu.uiuc.ncsa.security.delegation.token.impl.RefreshTokenImpl;
import edu.uiuc.ncsa.security.storage.data.MapConverter;
import edu.uiuc.ncsa.security.storage.sql.ConnectionPool;
import edu.uiuc.ncsa.security.storage.sql.ConnectionRecord;
import edu.uiuc.ncsa.security.storage.sql.internals.ColumnMap;
import edu.uiuc.ncsa.security.storage.sql.internals.Table;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import javax.inject.Provider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static edu.uiuc.ncsa.myproxy.oa4mp.oauth2.OA2ServiceTransaction.RFC862_STATE_KEY;

/**
 * <p>Created by Jeff Gaynor<br>
 * on 3/25/14 at  10:30 AM
 */
public class OA2SQLTStore<V extends OA2ServiceTransaction> extends DSSQLTransactionStore<V> implements OA2TStoreInterface<V> {
    @Override
    public String getCreationTSField() {
        return ((OA2TransactionKeys) getMapConverter().getKeys()).authTime();
    }

    public OA2SQLTStore(TokenForge tokenForge, ConnectionPool connectionPool, Table table, Provider<V> idp, MapConverter converter) {
        super(tokenForge, connectionPool, table, idp, converter);
    }

    @Override
    public V get(RefreshToken refreshToken) {
        return getByRefreshToken(refreshToken);
    }

    public V getByRefreshToken(RefreshToken refreshToken) {
        String identifier = ((RefreshTokenImpl) refreshToken).getJti().toString();
        return getTransaction(identifier, ((OA2TransactionTable) getTransactionTable()).getByRefreshTokenStatement());
    }

    @Override
    public List<V> getByUsername(String username) {
        String statement = ((OA2TransactionTable) getTransactionTable()).getByUsernameStatement();
        if (username == null) {
            throw new IllegalStateException("Error: a null identifier was supplied");
        }
        ConnectionRecord cr = getConnection();
        Connection c = cr.connection;
        List<V> list = new ArrayList<>();
        V t = null;
        try {
            PreparedStatement stmt = c.prepareStatement(statement);
            stmt.setString(1, username);
            stmt.executeQuery();
            ResultSet rs = stmt.getResultSet();
            while (rs.next()) {
                ColumnMap map = rsToMap(rs);
                t = create();
                populate(map, t);
                list.add(t);
            }
            if (!rs.next()) {
                rs.close();
                stmt.close();
                releaseConnection(cr);
                throw new TransactionNotFoundException("No transaction found for username \"" + username + "\"");
            }

            rs.close();
            stmt.close();
            releaseConnection(cr);
        } catch (SQLException e) {
            throw new GeneralException("Error getting transaction with username \"" + username + "\"", e);
        }
        return list;

    }

    @Override
    public Map<Identifier, List<TokenInfoRecord>> getTokenInfo(String username) {
        OA2TransactionTable table = (OA2TransactionTable) getTransactionTable();
        String statement = table.getTokenInfoStatement();
        if (username == null) {
            throw new IllegalStateException("Error: a null identifier was supplied");
        }
        ConnectionRecord cr = getConnection();
        Connection c = cr.connection;
        HashMap<Identifier, List<TokenInfoRecord>> records = new HashMap<>();
        List<TokenInfoRecord> list;
        TokenInfoRecord tir = null;
        try {
            PreparedStatement stmt = c.prepareStatement(statement);
            stmt.setString(1, username);
            stmt.executeQuery();
            ResultSet rs = stmt.getResultSet();

            while (rs.next()) {
                ColumnMap map = rsToMap(rs);
                tir = new TokenInfoRecord();
                tir.fromMap(map, table.getOA2Keys());
                Identifier clientID = tir.clientID;
                if (!records.containsKey(clientID)) {
                    records.put(clientID, new ArrayList<>());
                }
                records.get(clientID).add(tir);
            }

            rs.close();
            stmt.close();
            releaseConnection(cr);
        } catch (SQLException e) {
            throw new GeneralException("Error getting transaction with username \"" + username + "\"", e);
        }
        return records;

    }

    /**
     * Since this is  potentially a very intensive operation run only once at startup
     * this has been tweaked to exactly let the database grab the minimum and process it here.
     *
     * @return
     */
    @Override
    public List<RFC8628State> getPending() {
        List<RFC8628State> pending = new ArrayList<>();
        ConnectionRecord cr = getConnection();
        Connection c = cr.connection;
        OA2TransactionTable oa2TT = (OA2TransactionTable) getTable();
        try {
            PreparedStatement stmt = c.prepareStatement(oa2TT.getRFC8628());
            stmt.execute();// just execute() since executeQuery(x) would throw an exception regardless of content per JDBC spec.

            ResultSet rs = stmt.getResultSet();
            while (rs.next()) {
                String rawJSON = rs.getString(1);
                // This is buried in the transaction state. Unearth it directly
                // Makes the path from store to rfc8626 state as short as possible.
                if (!StringUtils.isTrivial(rawJSON)) {
                    JSONObject json = (JSONObject) JSONSerializer.toJSON(rawJSON);
                    JSONObject j = json.getJSONObject(RFC862_STATE_KEY);
                    RFC8628State state = new RFC8628State();
                    state.fromJSON(j);
                    pending.add(state);
                }
            }
            rs.close();
            stmt.close();
            releaseConnection(cr);
        } catch (SQLException e) {
            destroyConnection(cr);
            throw new GeneralException("Error: could not get database object", e);
        }

        return pending;
    }

    @Override
    public V getByProxyID(Identifier proxyID) {
        OA2TransactionTable oa2TT = (OA2TransactionTable) getTable();
        return getSingleValue(proxyID.toString(), oa2TT.getByProxyID());
    }

    @Override
    public V getByUserCode(String userCode) {
        OA2TransactionTable oa2TT = (OA2TransactionTable) getTable();
        return getSingleValue(userCode, oa2TT.getByUserCode());
    }

    public V getSingleValue(String targetString, String preparedStatement) {
        ConnectionRecord cr = getConnection();
        Connection c = cr.connection;
        OA2TransactionTable oa2TT = (OA2TransactionTable) getTable();
        V result = null;

        try {
            PreparedStatement stmt = c.prepareStatement(preparedStatement);
            stmt.setString(1, targetString);
            stmt.execute();// just execute() since executeQuery(x) would throw an exception regardless of content per JDBC spec.

            ResultSet rs = stmt.getResultSet();
            if (!rs.next()) {
                rs.close();
                stmt.close();
                releaseConnection(cr);
                return null;   // returning a null fulfills contract for this being a map.
            }

            ColumnMap map = rsToMap(rs);
            boolean tooManyresults = rs.next();
            rs.close();
            stmt.close();
            releaseConnection(cr);
            if (tooManyresults) {
                throw new IllegalStateException("error: Multiple transactions with \"" + targetString + "\" found.");
            }
            result = create();
            populate(map, result);
        } catch (SQLException e) {
            destroyConnection(cr);
            throw new GeneralException("Error: could not get database object", e);
        }

        return result;
    }

    /**
     * TODO - Improve this with a specific query later.
     *
     * @param userCode
     * @return
     */
    @Override
    public boolean hasUserCode(String userCode) {
        return getByUserCode(userCode) != null;
    }
}
