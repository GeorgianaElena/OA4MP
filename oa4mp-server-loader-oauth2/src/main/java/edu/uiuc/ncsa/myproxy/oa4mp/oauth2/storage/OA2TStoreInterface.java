package edu.uiuc.ncsa.myproxy.oa4mp.oauth2.storage;

import edu.uiuc.ncsa.myproxy.oa4mp.oauth2.OA2ServiceTransaction;
import edu.uiuc.ncsa.myproxy.oa4mp.oauth2.servlet.RFC8628State;
import edu.uiuc.ncsa.security.core.Identifier;
import edu.uiuc.ncsa.security.core.Store;
import edu.uiuc.ncsa.security.delegation.storage.TransactionStore;
import edu.uiuc.ncsa.security.delegation.token.RefreshToken;

import java.util.List;
import java.util.Map;

/**
 * <p>Created by Jeff Gaynor<br>
 * on 3/16/22 at  6:58 AM
 */
public interface OA2TStoreInterface<V extends OA2ServiceTransaction> extends Store<V>, TransactionStore<V>, RFC8628Store<V>, RefreshTokenStore<V>, UsernameFindable<V> {
 //   String getCreationTSField();

    @Override
    V get(RefreshToken refreshToken);

 //   V getByRefreshToken(RefreshToken refreshToken);

    @Override
    List<V> getByUsername(String username);

    Map<Identifier, List<TokenInfoRecord>> getTokenInfo(String username);

    @Override
    List<RFC8628State> getPending();

    @Override
    V getByProxyID(Identifier proxyID);

    @Override
    V getByUserCode(String userCode);

  //  V getSingleValue(String targetString, String preparedStatement);

    @Override
    boolean hasUserCode(String userCode);
}
