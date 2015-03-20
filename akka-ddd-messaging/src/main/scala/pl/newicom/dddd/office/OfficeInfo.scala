package pl.newicom.dddd.office

import org.json4s.FullTypeHints
import pl.newicom.dddd.serialization.JsonSerializationHints

import scala.reflect.ClassTag

object OfficeInfo {
  def apply[A](_serializationHints: JsonSerializationHints)(implicit ct: ClassTag[A]): OfficeInfo[A] =
    new OfficeInfo[A] {
      def serializationHints = _serializationHints
      def name = ct.runtimeClass.getSimpleName
    }

  def apply[A](_name: String, _serializationHints: JsonSerializationHints): OfficeInfo[A] =
    new OfficeInfo[A] {
      def serializationHints = _serializationHints
      def name = _name
    }

  def apply[A](_name: String, _streamName: String, _serializationHints: JsonSerializationHints): OfficeInfo[A] =
    new OfficeInfo[A] {
      def serializationHints = _serializationHints
      override def streamName = _streamName
      def name = _name
    }

  def apply[A](_name: String): OfficeInfo[A] =
    new OfficeInfo[A] {
      def serializationHints = new JsonSerializationHints {
        def typeHints = FullTypeHints(List())
        def serializers = List()
      }
      def name = _name
    }

}

trait OfficeInfo[A] {
  def name: String
  def streamName: String = name
  def serializationHints: JsonSerializationHints
}