package co.ledger.lama.bitcoin.worker

import co.ledger.lama.bitcoin.common.models.interpreter.AccountAddress
import co.ledger.lama.bitcoin.common.models.worker.ConfirmedTransaction

package object models {

  case class BatchResult(
      addresses: List[AccountAddress],
      transactions: List[ConfirmedTransaction],
      continue: Boolean
  )

}
