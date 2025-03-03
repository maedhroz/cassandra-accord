/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package accord.primitives;

import accord.utils.Invariants;

import accord.api.RoutingKey;
import accord.utils.SortedArrays;

import javax.annotation.Nonnull;

import static accord.utils.ArrayBuffers.cachedRoutingKeys;

public abstract class KeyRoute extends AbstractUnseekableKeys<Route<RoutingKey>> implements Route<RoutingKey>
{
    public final RoutingKey homeKey;

    KeyRoute(@Nonnull RoutingKey homeKey, RoutingKey[] keys)
    {
        super(keys);
        this.homeKey = Invariants.nonNull(homeKey);
    }

    @Override
    public Unseekables<RoutingKey, ?> toMaximalUnseekables()
    {
        return new RoutingKeys(SortedArrays.insert(keys, homeKey, RoutingKey[]::new));
    }

    @Override
    public Unseekables<RoutingKey, ?> with(Unseekables<RoutingKey, ?> with)
    {
        AbstractKeys<RoutingKey, ?> that = (AbstractKeys<RoutingKey, ?>) with;
        return wrap(SortedArrays.linearUnion(keys, that.keys, cachedRoutingKeys()), that);
    }

    @Override
    public RoutingKey homeKey()
    {
        return homeKey;
    }

    @Override
    public abstract PartialKeyRoute slice(Ranges ranges);

    @Override
    public Unseekables<RoutingKey, ?> slice(Ranges ranges, Slice slice)
    {
        return slice(ranges);
    }

    private AbstractUnseekableKeys<?> wrap(RoutingKey[] wrap, AbstractKeys<RoutingKey, ?> that)
    {
        return wrap == keys ? this : wrap == that.keys && that instanceof AbstractUnseekableKeys<?>
                ? (AbstractUnseekableKeys<?>) that
                : new RoutingKeys(wrap);
    }
}
