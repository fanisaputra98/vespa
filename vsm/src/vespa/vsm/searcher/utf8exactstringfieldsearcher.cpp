// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "utf8exactstringfieldsearcher.h"

using search::byte;
using search::QueryTerm;
using search::QueryTermList;

namespace vsm {

std::unique_ptr<FieldSearcher>
UTF8ExactStringFieldSearcher::duplicate() const
{
    return std::make_unique<UTF8ExactStringFieldSearcher>(*this);
}

size_t
UTF8ExactStringFieldSearcher::matchTerms(const FieldRef & f, const size_t mintsz)
{
    (void) mintsz;
    for (QueryTermList::iterator it = _qtl.begin(), mt = _qtl.end(); it != mt; ++it) {
        QueryTerm & qt = **it;
        matchTermExact(f, qt);
    }
    return 1;
}

size_t
UTF8ExactStringFieldSearcher::matchTerm(const FieldRef & f, QueryTerm & qt)
{
    return matchTermExact(f, qt);
}

}
